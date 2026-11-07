package com.xavier.backend.inventory.count.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Actor;
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
 * Approval of a submitted count (D119, D122 and their amendments, D113), one transaction with
 * {@code INVENTORY_COUNT_APPROVE}:
 * <ol>
 *     <li>the count's store is authorized first (D42), then the count row is locked;</li>
 *     <li>422 SELF_APPROVAL when the approver counted any line;</li>
 *     <li>409 CONSUMPTION_BACKLOG while a consumption publication of the store for a round (or void) at or before
 *     the latest cut-off is pending, and 409 CONSUMPTION_EXCEPTIONS_OPEN while the store has open consumption
 *     exceptions up to that cut-off: either way the expected quantity is not known yet;</li>
 *     <li>every balance row of the count is locked up front, in the ledger's order ({@link StockLedger#lock}), so
 *     sales posting on the same items wait and never deadlock, and the expected quantities are read under the
 *     lock;</li>
 *     <li>each line is settled at its own cut-off: expected = Σ movements with {@code occurred_at ≤ counted_at}, so
 *     a sale after the cut-off is not counted against it;</li>
 *     <li>the differences post in one {@link StockLedger#post} call, source {@code STOCK_COUNT} (the count id, the
 *     line id) with {@code occurred_at = counted_at}: COUNT_ADJUSTMENT at the average, or for an opening OPENING
 *     at the entered cost. A replay posts nothing new.</li>
 * </ol>
 * An opening is settled like any count (sales since it started are absorbed). It must be the warehouse's first
 * fact: 422 OPENING_NOT_FIRST for an earlier approved opening or a non-consumption movement at or before its
 * cut-off. Starting it made the store live ({@link StockCountService#start}).
 */
@Service
public class CountApprovalService {

    /** The listeners of S47's consumption package (round and void consumption). */
    static final String CONSUMPTION_LISTENERS = "com.xavier.backend.inventory.consumption";

    private final StockCountRepository counts;
    private final CountLedgerQueries ledgerQueries;
    private final StockLedger ledger;
    private final BusinessDates businessDates;
    private final AccessPolicy accessPolicy;
    private final AuditLog auditLog;
    private final Clock clock;

    CountApprovalService(StockCountRepository counts, CountLedgerQueries ledgerQueries, StockLedger ledger,
                         BusinessDates businessDates, AccessPolicy accessPolicy,
                         AuditLog auditLog, Clock clock) {
        this.counts = counts;
        this.ledgerQueries = ledgerQueries;
        this.ledger = ledger;
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
        Actor approver = accessPolicy.current();
        if (count.recordedBy(approver.kind().name(), StockCountService.recordedId(approver))) {
            throw CountRuleException.selfApproval(id);
        }
        Instant cutOff = count.latestCutOff();
        long pending = ledgerQueries.pendingConsumption(CONSUMPTION_LISTENERS, storeId, cutOff);
        if (pending > 0) {
            throw CountConflictException.consumptionBacklog(storeId, pending);
        }
        long exceptions = ledgerQueries.openExceptions(storeId, cutOff);
        if (exceptions > 0) {
            throw CountConflictException.consumptionExceptionsOpen(storeId, exceptions);
        }
        UUID warehouseId = count.getWarehouseId();
        ledger.lock(count.getLines().stream().map(line -> new BalanceKey(warehouseId, line.getStockItemId()))
                .toList());
        if (count.getKind() == CountKind.OPENING && !isFirst(count)) {
            throw CountRuleException.openingNotFirst(warehouseId);
        }
        Map<UUID, Instant> cutOffs = new LinkedHashMap<>();
        count.getLines().forEach(line -> cutOffs.put(line.getStockItemId(), line.getCountedAt()));
        Instant now = clock.instant();
        UUID accountId = approver.userId();
        count.approve(ledgerQueries.quantitiesAt(warehouseId, cutOffs), accountId, now);
        ledger.post(movements(count));
        StockCount saved = UniqueConstraints.saveAndFlush(counts, count,
                () -> CountRuleException.openingNotFirst(warehouseId), "stock_count_opening_ux");
        auditLog.recordAsCurrentActor("STOCK_COUNT_APPROVED", StockCountService.ENTITY, id.toString(), storeId, null,
                null, Map.of("warehouseId", warehouseId.toString(), "kind", count.getKind().name()));
        return StockCountService.view(saved);
    }

    /**
     * Under the balance locks: no approved opening in the warehouse, and no movement other than consumption at or
     * before the latest cut-off (D122 amendment). Sales since the store went live are absorbed: the opening posts
     * counted − expected. The partial unique index settles two racing openings.
     */
    private boolean isFirst(StockCount count) {
        return !counts.existsByWarehouseIdAndKindAndStatus(count.getWarehouseId(), CountKind.OPENING,
                CountStatus.APPROVED)
                && !ledgerQueries.hasNonConsumptionMovementAtOrBefore(count.getWarehouseId(), count.latestCutOff());
    }

    private List<PostMovement> movements(StockCount count) {
        List<PostMovement> movements = new ArrayList<>();
        for (StockCountLine line : count.getLines()) {
            if (line.getDifference().signum() == 0) {
                continue;
            }
            // an opening raises stock at the entered cost; a shortfall (only after consumption reversals) is a
            // plain adjustment at the average, as in any count
            boolean opening = count.getKind() == CountKind.OPENING && line.getDifference().signum() > 0;
            PostMovement movement = PostMovement.of(count.getWarehouseId(), line.getStockItemId(),
                            opening ? MovementType.OPENING : MovementType.COUNT_ADJUSTMENT, line.getDifference(),
                            CountLedgerQueries.SOURCE_TYPE, count.getId(),
                            businessDates.of(count.getStoreId(), line.getCountedAt()), line.getCountedAt())
                    .withSourceLine(line.getId()).withReason(count.getKind() == CountKind.OPENING ? "opening count"
                            : "stock count");
            movements.add(opening ? movement.atCost(line.getUnitCost()) : movement);
        }
        return movements;
    }
}
