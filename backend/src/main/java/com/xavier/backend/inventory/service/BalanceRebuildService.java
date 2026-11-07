package com.xavier.backend.inventory.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.model.BalanceKey;
import com.xavier.backend.inventory.model.StockPosition;
import com.xavier.backend.inventory.model.dto.RebuildRequest;
import com.xavier.backend.inventory.model.dto.RebuildView;
import com.xavier.backend.inventory.repository.LedgerStore;
import com.xavier.backend.inventory.repository.BalanceProjection;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rebuilds the balance projection from the ledger (D113): the balance rows are locked in the ledger's order (so no
 * posting runs meanwhile), every movement is replayed in {@code posting_seq} order through
 * {@link StockPosition#after}, the rule posting used, and each row that differs is rewritten. {@code INVENTORY_REBUILD}
 * in the warehouse's store, or installation-wide for every warehouse, one warehouse after the other (never one
 * statement over every balance row). Audited.
 */
@Service
public class BalanceRebuildService {

    private final BalanceProjection projection;
    private final LedgerStore ledger;
    private final InventoryScope scope;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    BalanceRebuildService(BalanceProjection projection, LedgerStore ledger, InventoryScope scope,
                          AccessPolicy accessPolicy, AuditLog auditLog, Clock clock) {
        this.projection = projection;
        this.ledger = ledger;
        this.scope = scope;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional
    public RebuildView rebuild(RebuildRequest request) {
        UUID warehouseId = request.warehouseId();
        UUID storeId = null;
        if (warehouseId == null) {
            accessPolicy.require(Permission.INVENTORY_REBUILD);
        } else {
            storeId = scope.requireWarehouse(Permission.INVENTORY_REBUILD, warehouseId);
        }
        List<UUID> warehouses = warehouseId == null ? projection.warehousesWithStock() : List.of(warehouseId);
        int balances = 0;
        int corrected = 0;
        for (UUID warehouse : warehouses) { // in lock order: one warehouse's rows sort together
            int[] counts = rebuildWarehouse(warehouse);
            balances += counts[0];
            corrected += counts[1];
        }
        auditLog.recordAsCurrentActor("STOCK_BALANCES_REBUILT", "StockBalance",
                warehouseId == null ? "ALL" : warehouseId.toString(), storeId, null, null,
                Map.of("warehouses", warehouses.size(), "balances", balances, "corrected", corrected));
        return new RebuildView(warehouseId, balances, corrected);
    }

    /** Locks one warehouse's balance rows, replays its ledger and rewrites what differs: {balances, corrected}. */
    private int[] rebuildWarehouse(UUID warehouseId) {
        Instant now = clock.instant();
        List<BalanceKey> keys = projection.keys(warehouseId).stream().sorted(BalanceKey.LOCK_ORDER).toList();
        if (keys.isEmpty()) {
            return new int[] {0, 0};
        }
        ledger.ensureBalances(keys, now);
        Map<BalanceKey, StockPosition> stored = ledger.lock(keys);
        Map<BalanceKey, StockPosition> replayed = new HashMap<>();
        Map<BalanceKey, Long> lastSeq = new HashMap<>();
        projection.replay(warehouseId, row -> {
            replayed.merge(row.key(), StockPosition.empty().after(row.quantity(), row.value(), row.unitCost()),
                    (before, ignored) -> before.after(row.quantity(), row.value(), row.unitCost()));
            lastSeq.put(row.key(), row.postingSeq());
        });
        int corrected = 0;
        for (BalanceKey key : keys) {
            StockPosition truth = replayed.getOrDefault(key, StockPosition.empty());
            if (!truth.equals(stored.get(key))) {
                ledger.updateBalance(key, truth, lastSeq.getOrDefault(key, 0L), now);
                corrected++;
            }
        }
        return new int[] {keys.size(), corrected};
    }
}
