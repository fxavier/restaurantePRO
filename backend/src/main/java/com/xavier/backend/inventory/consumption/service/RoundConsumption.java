package com.xavier.backend.inventory.consumption.service;

import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.consumption.model.ExceptionKind;
import com.xavier.backend.inventory.consumption.repository.ConsumptionBook;
import com.xavier.backend.inventory.consumption.service.ConsumptionPlanner.Context;
import com.xavier.backend.inventory.consumption.service.ConsumptionPlanner.Plan;
import com.xavier.backend.inventory.model.BalanceKey;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.service.ActivationService;
import com.xavier.backend.inventory.service.StockLedger;
import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.BatchFacts.BatchLineFact;
import com.xavier.backend.orders.OrdersApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes a confirmed round (D116, D122): pulls its facts from orders, and — when inventory is live in the store and
 * the round was confirmed at or after go-live — posts the SALE_CONSUMPTION movements of every sold unit in one ledger
 * call, records each unresolved unit as a consumption exception (the rest of the round still posts), and marks each
 * settled line (consumed, or consumed for nothing) so its voids apply. Idempotent: a settled line is never planned
 * again, and the ledger's source key catches the rest. It never blocks a sale: it runs in the listener's own
 * transaction, after the round committed.
 */
@Service
public class RoundConsumption {

    private final OrdersApi orders;
    private final ActivationService activations;
    private final ConsumptionPlanner planner;
    private final ConsumptionBook book;
    private final StockLedger ledger;
    private final VoidReversal voids;
    private final AuditLog auditLog;
    private final Clock clock;

    RoundConsumption(OrdersApi orders, ActivationService activations, ConsumptionPlanner planner,
                     ConsumptionBook book, StockLedger ledger, VoidReversal voids, AuditLog auditLog, Clock clock) {
        this.orders = orders;
        this.activations = activations;
        this.planner = planner;
        this.book = book;
        this.ledger = ledger;
        this.voids = voids;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /**
     * @return the movements this delivery posted: a redelivery posts nothing, because a line consumption already
     *         settled is never planned again (its route, policy or recipe may have changed since)
     */
    @Transactional
    public List<MovementView> consume(UUID batchId) {
        BatchFacts round = orders.batchFacts(batchId);
        Optional<Instant> live = activations.activeFrom(round.storeId());
        List<UUID> lines = round.lines().stream().map(BatchLineFact::lineId).toList();
        book.lockLines(lines);
        Map<UUID, BigDecimal> settled = new LinkedHashMap<>();
        if (live.isEmpty() || round.confirmedAt().isBefore(live.get())) {
            // D122: a store not live, or history before go-live, consumes nothing; its lines are settled for
            // nothing, so a void of them never waits (also once the store goes live later)
            round.lines().forEach(line -> settled.put(line.lineId(), line.quantity()));
            return post(round, settled, List.of());
        }
        Context context = planner.context(round);
        List<PostMovement> movements = new ArrayList<>();
        Instant now = clock.instant();
        for (BatchLineFact line : round.lines()) {
            if (book.consumedQuantity(line.lineId()).isPresent()) {
                continue; // settled by an earlier delivery; what it left in exception goes through reprocess
            }
            boolean posted = false;
            boolean unresolved = false;
            for (SoldUnit unit : SoldUnit.of(line)) {
                if (book.hasException(unit.sourceType(), unit.sourceId())) {
                    unresolved = true; // settled only by its reprocess, never by a redelivery
                    continue;
                }
                Plan plan = planner.plan(context, unit, false);
                if (plan.unresolved() != null) {
                    recordException(round, unit, plan.unresolved(), now);
                    unresolved = true;
                    continue;
                }
                movements.addAll(plan.movements());
                posted |= !plan.movements().isEmpty();
            }
            if (posted || !unresolved) {
                settled.put(line.lineId(), line.quantity());
            }
        }
        return post(round, settled, movements);
    }

    /**
     * Posts {@code movements} of {@code round}, marks {@code settledLines} (line → confirmed quantity) as settled,
     * then applies every void of those lines. Locks: the lines first, then every balance of the new movements and
     * of the lines' earlier sale movements, in the ledger's order (D132).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MovementView> post(BatchFacts round, Map<UUID, BigDecimal> settledLines,
                                   List<PostMovement> movements) {
        book.lockLines(settledLines.keySet());
        List<BalanceKey> keys = new ArrayList<>(movements.stream()
                .map(m -> new BalanceKey(m.warehouseId(), m.stockItemId())).toList());
        settledLines.keySet().forEach(line -> book.saleMovementsOf(line).forEach(m -> keys.add(VoidReversal.keyOf(m))));
        ledger.lock(keys);
        List<MovementView> posted = new ArrayList<>(ledger.post(movements));
        settledLines.forEach((line, quantity) -> {
            book.recordConsumedLine(line, round.batchId(), round.storeId(), quantity, round.confirmedAt());
            posted.addAll(voids.settle(line, book.consumedQuantity(line).orElse(quantity)));
        });
        return posted;
    }

    private void recordException(BatchFacts round, SoldUnit unit, ExceptionKind kind, Instant now) {
        if (book.recordException(round.storeId(), unit.sourceType(), unit.sourceId(), unit.itemId(), kind,
                round.batchId(), unit.lineId(), round.confirmedAt(), now)) {
            auditLog.recordAsSystem("CONSUMPTION_EXCEPTION_RAISED", "ConsumptionException",
                    unit.sourceId().toString(), round.storeId(), null, kind.name(),
                    Map.of("sourceType", unit.sourceType(), "batchId", round.batchId().toString(),
                            "lineId", unit.lineId().toString()));
        }
    }
}
