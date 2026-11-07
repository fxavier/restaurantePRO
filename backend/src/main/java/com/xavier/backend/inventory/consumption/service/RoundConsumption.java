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
 * call, records each unresolved unit as a consumption exception (the rest of the round still posts), and remembers
 * the consumed lines for their voids. Idempotent: a redelivered round replays the same movements (the ledger's source
 * key) and records nothing new. It never blocks a sale: it runs in the listener's own transaction, after the round
 * committed.
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

    /** @return the movements of the round, as posted or as recorded by an earlier delivery */
    @Transactional
    public List<MovementView> consume(UUID batchId) {
        BatchFacts round = orders.batchFacts(batchId);
        Optional<Instant> live = activations.activeFrom(round.storeId());
        if (live.isEmpty() || round.confirmedAt().isBefore(live.get())) {
            return List.of(); // D122: history before go-live is never consumed
        }
        Context context = planner.context(round);
        Map<UUID, BigDecimal> consumedLines = new LinkedHashMap<>();
        List<PostMovement> movements = new ArrayList<>();
        Instant now = clock.instant();
        for (BatchLineFact line : round.lines()) {
            for (SoldUnit unit : SoldUnit.of(line)) {
                Plan plan = planner.plan(context, unit, false);
                if (plan.unresolved() != null) {
                    recordException(round, unit, plan.unresolved(), now);
                    continue;
                }
                movements.addAll(plan.movements());
                if (!plan.movements().isEmpty()) {
                    consumedLines.put(line.lineId(), line.quantity());
                }
            }
        }
        return post(round, consumedLines, movements);
    }

    /**
     * Posts {@code movements} of {@code round} and records {@code consumedLines} (line → confirmed quantity), then
     * reverses the voids that were waiting for those lines. Lines first, then every balance, both in a fixed order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MovementView> post(BatchFacts round, Map<UUID, BigDecimal> consumedLines,
                                   List<PostMovement> movements) {
        book.lockLines(consumedLines.keySet());
        ledger.lock(movements.stream().map(m -> new BalanceKey(m.warehouseId(), m.stockItemId())).toList());
        List<MovementView> posted = new ArrayList<>(ledger.post(movements));
        consumedLines.forEach((line, quantity) -> {
            book.recordConsumedLine(line, round.batchId(), round.storeId(), quantity, round.confirmedAt());
            posted.addAll(voids.reversePending(line, book.consumedQuantity(line).orElse(quantity)));
        });
        return posted;
    }

    private void recordException(BatchFacts round, SoldUnit unit, ExceptionKind kind, Instant now) {
        if (book.recordException(round.storeId(), unit.sourceType(), unit.sourceId(), unit.itemId(), kind,
                round.batchId(), unit.lineId(), now)) {
            auditLog.recordAsSystem("CONSUMPTION_EXCEPTION_RAISED", "ConsumptionException",
                    unit.sourceId().toString(), round.storeId(), null, kind.name(),
                    Map.of("sourceType", unit.sourceType(), "batchId", round.batchId().toString(),
                            "lineId", unit.lineId().toString()));
        }
    }
}
