package com.xavier.backend.inventory.consumption.service;

import com.xavier.backend.audit.AuditLog;
import com.xavier.backend.inventory.consumption.repository.ConsumptionBook;
import com.xavier.backend.inventory.consumption.repository.ConsumptionBook.SaleMovement;
import com.xavier.backend.inventory.model.BalanceKey;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.StockPosition;
import com.xavier.backend.inventory.model.dto.MovementView;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.service.ActivationService;
import com.xavier.backend.inventory.service.StockLedger;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.VoidFacts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles the voids of a round line against its consumption (D117, amended). A void names, through
 * {@code VoidFacts.consumedLineId}, the round line it descends from (through transfers and splits). Every void of a
 * line is kept ({@code line_void}) and applied to each SALE_CONSUMPTION movement of the line once, so a line consumed
 * in parts (menu components reprocessed one by one) applies all its voids to every part. A void's share of a movement
 * is {@code consumed × voided ÷ line quantity}, capped at what earlier voids left; once the voids add up to the line
 * quantity, the last one still to apply to a movement takes its whole remainder (no rounding residue).
 * <ul>
 *     <li>Not wasted (an error, nothing prepared): a CONSUMPTION_REVERSAL IN at the original movement's unit
 *     cost.</li>
 *     <li>Wasted: no stock movement (the ingredients really were used); the share is reclassified from sale to waste
 *     at the original unit cost in an append-only {@code waste_record}, audited.</li>
 * </ul>
 * A void of a line not settled yet waits for it, unless it was voided before the store went live.
 *
 * <p>Reversal sources: {@code VOID / voidId} for a line's own movement; {@code VOID_COMPONENT} with an id derived
 * from the void and the component for a menu component's, so each original movement has its own reversal key.
 */
@Service
public class VoidReversal {

    static final String VOID = "VOID";
    static final String VOID_COMPONENT = "VOID_COMPONENT";

    private final OrdersApi orders;
    private final ConsumptionBook book;
    private final StockLedger ledger;
    private final ActivationService activations;
    private final AuditLog auditLog;
    private final Clock clock;

    VoidReversal(OrdersApi orders, ConsumptionBook book, StockLedger ledger, ActivationService activations,
                 AuditLog auditLog, Clock clock) {
        this.orders = orders;
        this.book = book;
        this.ledger = ledger;
        this.activations = activations;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** The void listener's transaction. */
    @Transactional
    public List<MovementView> reverse(UUID voidId) {
        VoidFacts facts = orders.voidFacts(voidId);
        UUID line = facts.consumedLineId();
        if (line == null) {
            return List.of(); // an R3 void with no round line
        }
        book.lockLines(List.of(line));
        Optional<BigDecimal> lineQuantity = book.consumedQuantity(line);
        if (lineQuantity.isEmpty() && !mayStillBeConsumed(facts)) {
            return List.of();
        }
        book.recordLineVoid(voidId, line, facts.storeId(), clock.instant());
        if (lineQuantity.isEmpty()) {
            return List.of(); // settled when the line is consumed
        }
        ledger.lock(book.saleMovementsOf(line).stream().map(VoidReversal::keyOf).toList());
        return settle(line, lineQuantity.get());
    }

    /**
     * Applies every void of {@code lineId} to every sale movement of the line it has not been applied to yet. The
     * caller locked the line and the balances of its sale movements.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MovementView> settle(UUID lineId, BigDecimal lineQuantity) {
        List<SaleMovement> originals = book.saleMovementsOf(lineId);
        List<UUID> voids = book.lineVoids(lineId);
        if (originals.isEmpty() || voids.isEmpty()) {
            return List.of();
        }
        Map<UUID, BigDecimal> open = new HashMap<>();
        originals.forEach(m -> open.put(m.id(), m.consumed().subtract(book.settledQuantity(m.id()))));
        List<VoidFacts> all = voids.stream().map(orders::voidFacts).toList();
        boolean lineFullyVoided = all.stream().map(VoidFacts::quantity).reduce(BigDecimal.ZERO, BigDecimal::add)
                .compareTo(lineQuantity) >= 0;
        Map<UUID, List<Source>> unsettled = new HashMap<>();
        for (SaleMovement original : originals) {
            unsettled.put(original.id(), all.stream().map(facts -> sourceOf(facts.voidId(), original))
                    .filter(source -> !book.settledBy(original.id(), source.voidId(), source.type(), source.id()))
                    .toList());
        }
        List<PostMovement> reversals = new ArrayList<>();
        Instant now = clock.instant();
        for (VoidFacts facts : all) {
            int wasted = 0;
            for (SaleMovement original : originals) {
                List<Source> pending = unsettled.get(original.id());
                Source source = sourceOf(facts.voidId(), original);
                if (!pending.contains(source)) {
                    continue;
                }
                // the last void still to apply to a fully voided line takes whatever rounding left
                boolean takesTheRest = lineFullyVoided && pending.getLast().equals(source);
                BigDecimal left = open.get(original.id());
                BigDecimal quantity = takesTheRest ? left : original.consumed().multiply(facts.quantity())
                        .divide(lineQuantity, StockPosition.QUANTITY_SCALE, RoundingMode.HALF_UP).min(left);
                if (quantity.signum() <= 0) {
                    continue;
                }
                open.put(original.id(), left.subtract(quantity));
                if (facts.wasted()) {
                    book.recordWaste(facts.voidId(), original, lineId, quantity, quantity
                                    .multiply(original.unitCost()).setScale(StockPosition.VALUE_SCALE,
                                            RoundingMode.HALF_UP), facts.businessDay(), facts.at(), facts.reason(),
                            now);
                    wasted++;
                } else {
                    reversals.add(new PostMovement(original.warehouseId(), original.stockItemId(),
                            MovementType.CONSUMPTION_REVERSAL, quantity, original.unitCost(), null, source.type(),
                            source.id(), lineId, original.id(), original.recipeVersionId(),
                            original.policyVersionId(), original.saleItemId(), facts.businessDay(), facts.at(),
                            facts.reason(), false));
                }
            }
            if (wasted > 0) {
                auditLog.recordAsSystem("CONSUMPTION_WASTE_RECORDED", "OrderLineVoid", facts.voidId().toString(),
                        facts.storeId(), null, facts.reason(),
                        Map.of("lineId", lineId.toString(), "records", wasted));
            }
        }
        return ledger.post(reversals);
    }

    /** A line voided before go-live (or in a store not live) was confirmed before it too, so it is never consumed. */
    private boolean mayStillBeConsumed(VoidFacts facts) {
        return activations.activeFrom(facts.storeId()).filter(live -> !facts.at().isBefore(live)).isPresent();
    }

    private static Source sourceOf(UUID voidId, SaleMovement original) {
        return SoldUnit.COMPONENT.equals(original.sourceType())
                ? new Source(voidId, VOID_COMPONENT, componentSource(voidId, original.sourceId()))
                : new Source(voidId, VOID, voidId);
    }

    /** The reversal source of one menu component in one void: stable, so a redelivery finds it. */
    static UUID componentSource(UUID voidId, UUID componentId) {
        return UUID.nameUUIDFromBytes((voidId + ":" + componentId).getBytes(StandardCharsets.UTF_8));
    }

    static BalanceKey keyOf(SaleMovement movement) {
        return new BalanceKey(movement.warehouseId(), movement.stockItemId());
    }

    private record Source(UUID voidId, String type, UUID id) {
    }
}
