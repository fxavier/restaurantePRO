package com.xavier.backend.inventory.count.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.count.exception.CountConflictException;
import com.xavier.backend.inventory.count.exception.CountRuleException;
import com.xavier.backend.inventory.count.model.CountKind;
import com.xavier.backend.inventory.count.model.CountStatus;
import com.xavier.backend.inventory.count.model.StockCount;
import com.xavier.backend.inventory.count.model.StockCountLine;
import com.xavier.backend.inventory.count.model.dto.CountView;
import com.xavier.backend.inventory.count.repository.CountLedgerQueries;
import com.xavier.backend.inventory.count.repository.StockCountRepository;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.model.BalanceKey;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.service.BusinessDates;
import com.xavier.backend.inventory.service.StockLedger;
import com.xavier.backend.shared.events.EventPublicationStats;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approval of a submitted count (D119, D122, D113), one transaction with {@code INVENTORY_COUNT_APPROVE}:
 * <ol>
 *     <li>the count's store is authorized first (D42), then the count row is locked;</li>
 *     <li>refused (409 CONSUMPTION_BACKLOG) while any consumption publication is pending: a round confirmed before
 *     a cut-off may not be posted yet, so the expected quantity would be wrong;</li>
 *     <li>every balance row of the count is locked up front, in the ledger's order ({@link StockLedger#lock}), so
 *     sales posting on the same items wait and never deadlock, and the expected quantities are read under the
 *     lock;</li>
 *     <li>each line is settled at its own cut-off: expected = Σ movements with {@code occurred_at ≤ counted_at}, so
 *     a sale after the cut-off is not counted against it;</li>
 *     <li>the differences post in one {@link StockLedger#post} call, source {@code STOCK_COUNT} (the count id, the
 *     line id) with {@code occurred_at = counted_at}: COUNT_ADJUSTMENT at the average, or OPENING at the entered
 *     cost. A replay posts nothing new.</li>
 * </ol>
 * An opening must be the warehouse's first fact (422 OPENING_NOT_FIRST: another opening, or any movement at or
 * before its cut-offs) and makes the store live if it is not yet ({@code active_from} = the approval instant).
 */
@Service
public class CountApprovalService {

    /** The listeners of S47's consumption package (RoundConsumptionListener, VoidConsumptionListener). */
    static final String CONSUMPTION_LISTENERS = "com.xavier.backend.inventory.consumption";

    private final StockCountRepository counts;
    private final CountLedgerQueries ledgerQueries;
    private final StockLedger ledger;
    private final EventPublicationStats publications;
    private final BusinessDates businessDates;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    CountApprovalService(StockCountRepository counts, CountLedgerQueries ledgerQueries, StockLedger ledger,
                         EventPublicationStats publications, BusinessDates businessDates, AccessPolicy accessPolicy,
                         AuditLog auditLog, Clock clock) {
        this.counts = counts;
        this.ledgerQueries = ledgerQueries;
        this.ledger = ledger;
        this.publications = publications;
        this.businessDates = businessDates;
        this.accessPolicy = accessPolicy;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional
    public CountView approve(UUID id) {
        UUID storeId = counts.storeOf(id)
                .orElseThrow(() -> new InventoryNotFoundException(StockCountService.ENTITY, id));
        accessPolicy.require(Permission.INVENTORY_COUNT_APPROVE, storeId);
        StockCount count = counts.findForUpdate(id)
                .orElseThrow(() -> new InventoryNotFoundException(StockCountService.ENTITY, id));
        count.requireApprovable();
        long pending = publications.pendingFor(CONSUMPTION_LISTENERS);
        if (pending > 0) {
            throw CountConflictException.consumptionBacklog(pending);
        }
        UUID warehouseId = count.getWarehouseId();
        ledger.lock(count.getLines().stream().map(line -> new BalanceKey(warehouseId, line.getStockItemId()))
                .toList());
        boolean opening = count.getKind() == CountKind.OPENING;
        if (opening && !isFirst(count)) {
            throw CountRuleException.openingNotFirst(warehouseId);
        }
        Map<UUID, Instant> cutOffs = new LinkedHashMap<>();
        count.getLines().forEach(line -> cutOffs.put(line.getStockItemId(), line.getCountedAt()));
        Instant now = clock.instant();
        UUID accountId = accessPolicy.current().userId();
        count.approve(ledgerQueries.quantitiesAt(warehouseId, cutOffs), accountId, now);
        ledger.post(movements(count));
        StockCount saved = UniqueConstraints.saveAndFlush(counts, count,
                () -> CountRuleException.openingNotFirst(warehouseId), "stock_count_opening_ux");
        if (opening && ledgerQueries.activateIfAbsent(storeId, now, accountId, now)) {
            auditLog.recordAsCurrentActor("INVENTORY_ACTIVATED", "InventoryActivation", storeId.toString(), storeId,
                    null, null, Map.of("activeFrom", now.toString(), "openingCountId", id.toString()));
        }
        auditLog.recordAsCurrentActor("STOCK_COUNT_APPROVED", StockCountService.ENTITY, id.toString(), storeId, null,
                null, Map.of("warehouseId", warehouseId.toString(), "kind", count.getKind().name()));
        return StockCountService.view(saved);
    }

    /**
     * Under the balance locks: no approved opening in the warehouse, and no movement at or before the latest
     * cut-off (so every line's expected quantity is zero). The partial unique index settles two racing openings.
     */
    private boolean isFirst(StockCount count) {
        return !counts.existsByWarehouseIdAndKindAndStatus(count.getWarehouseId(), CountKind.OPENING,
                CountStatus.APPROVED)
                && !ledgerQueries.hasMovementAtOrBefore(count.getWarehouseId(), count.latestCutOff());
    }

    private List<PostMovement> movements(StockCount count) {
        List<PostMovement> movements = new ArrayList<>();
        for (StockCountLine line : count.getLines()) {
            if (line.getDifference().signum() == 0) {
                continue;
            }
            boolean opening = count.getKind() == CountKind.OPENING;
            PostMovement movement = PostMovement.of(count.getWarehouseId(), line.getStockItemId(),
                            opening ? MovementType.OPENING : MovementType.COUNT_ADJUSTMENT, line.getDifference(),
                            CountLedgerQueries.SOURCE_TYPE, count.getId(),
                            businessDates.of(count.getStoreId(), line.getCountedAt()), line.getCountedAt())
                    .withSourceLine(line.getId()).withReason(opening ? "opening count" : "stock count");
            movements.add(opening ? movement.atCost(line.getUnitCost()) : movement);
        }
        return movements;
    }
}
