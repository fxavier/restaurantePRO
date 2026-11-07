package com.xavier.backend.inventory.consumption.service;

import com.xavier.backend.inventory.consumption.repository.ConsumptionBook;
import com.xavier.backend.inventory.consumption.repository.ConsumptionBook.SaleMovement;
import com.xavier.backend.inventory.model.BalanceKey;
import com.xavier.backend.inventory.model.MovementType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reverses the consumption of a voided line (D117). The void names, through {@code VoidFacts.consumedLineId}, the
 * round line it descends from (through transfers and splits), whose SALE_CONSUMPTION movements are reversed in
 * proportion: {@code consumed × voided ÷ line quantity}, capped at what earlier voids left unreversed. Each reversal is
 * a CONSUMPTION_REVERSAL IN at the original movement's unit cost; a wasted void adds a WASTE OUT of the same quantity,
 * so the stock does not change and the cost moves from sale to waste. A void whose line is not consumed yet waits
 * for it ({@code pending_void}) unless it was voided before the store went live; a line never consumed (before
 * go-live, untracked, an exception) reverses nothing.
 *
 * <p>Sources: a reversal of a line's own movement is {@code VOID / voidId}; of a menu component's,
 * {@code VOID_COMPONENT} with an id derived from the void and the component, so each original movement has its own
 * reversal key. A redelivered void finds its reversals and posts nothing.
 */
@Service
public class VoidReversal {

    static final String VOID = "VOID";
    static final String VOID_COMPONENT = "VOID_COMPONENT";

    private final OrdersApi orders;
    private final ConsumptionBook book;
    private final StockLedger ledger;
    private final ActivationService activations;
    private final Clock clock;

    VoidReversal(OrdersApi orders, ConsumptionBook book, StockLedger ledger, ActivationService activations,
                 Clock clock) {
        this.orders = orders;
        this.book = book;
        this.ledger = ledger;
        this.activations = activations;
        this.clock = clock;
    }

    /** The void listener's transaction. */
    @Transactional
    public List<MovementView> reverse(UUID voidId) {
        VoidFacts facts = orders.voidFacts(voidId);
        if (facts.consumedLineId() == null) {
            return List.of(); // an R3 void with no round line
        }
        book.lockLines(List.of(facts.consumedLineId()));
        Optional<BigDecimal> lineQuantity = book.consumedQuantity(facts.consumedLineId());
        if (lineQuantity.isEmpty()) {
            if (mayStillBeConsumed(facts)) {
                book.recordPendingVoid(voidId, facts.consumedLineId(), facts.storeId(), clock.instant());
            }
            return List.of();
        }
        List<SaleMovement> originals = book.saleMovementsOf(facts.consumedLineId());
        ledger.lock(originals.stream().map(m -> new BalanceKey(m.warehouseId(), m.stockItemId())).toList());
        return reverseLocked(facts, lineQuantity.get(), originals);
    }

    /**
     * Reverses the voids that waited for {@code lineId}, which the caller consumed in this transaction after
     * locking the line and the balances of its movements.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<MovementView> reversePending(UUID lineId, BigDecimal lineQuantity) {
        List<MovementView> posted = new ArrayList<>();
        List<SaleMovement> originals = book.saleMovementsOf(lineId);
        for (UUID voidId : book.pendingVoids(lineId)) {
            posted.addAll(reverseLocked(orders.voidFacts(voidId), lineQuantity, originals));
        }
        return posted;
    }

    /** A line voided before go-live (or in a store not live) was confirmed before it too, so it is never consumed. */
    private boolean mayStillBeConsumed(VoidFacts facts) {
        return activations.activeFrom(facts.storeId()).filter(live -> !facts.at().isBefore(live)).isPresent();
    }

    private List<MovementView> reverseLocked(VoidFacts facts, BigDecimal lineQuantity, List<SaleMovement> originals) {
        book.removePendingVoid(facts.voidId());
        if (alreadyReversed(facts.voidId(), originals)) {
            return List.of();
        }
        List<PostMovement> movements = new ArrayList<>();
        for (SaleMovement original : originals) {
            BigDecimal share = original.consumed().multiply(facts.quantity())
                    .divide(lineQuantity, 6, RoundingMode.HALF_UP);
            BigDecimal open = original.consumed().subtract(book.reversedQuantity(original.id()));
            BigDecimal quantity = share.min(open);
            if (quantity.signum() <= 0) {
                continue;
            }
            movements.add(movement(facts, original, MovementType.CONSUMPTION_REVERSAL, quantity));
            if (facts.wasted()) {
                movements.add(movement(facts, original, MovementType.WASTE, quantity.negate()));
            }
        }
        return ledger.post(movements);
    }

    private boolean alreadyReversed(UUID voidId, List<SaleMovement> originals) {
        List<UUID> components = originals.stream().filter(m -> SoldUnit.COMPONENT.equals(m.sourceType()))
                .map(m -> componentSource(voidId, m.sourceId())).distinct().toList();
        return book.anyMovementOf(VOID, List.of(voidId)) || book.anyMovementOf(VOID_COMPONENT, components);
    }

    /** IN at the original's unit cost; the WASTE OUT names it too (an OUT is valued at the average, D113). */
    private static PostMovement movement(VoidFacts facts, SaleMovement original, MovementType type,
                                         BigDecimal quantity) {
        boolean component = SoldUnit.COMPONENT.equals(original.sourceType());
        return new PostMovement(original.warehouseId(), original.stockItemId(), type, quantity,
                original.unitCost(), null, component ? VOID_COMPONENT : VOID,
                component ? componentSource(facts.voidId(), original.sourceId()) : facts.voidId(),
                facts.consumedLineId(), original.id(), original.recipeVersionId(), original.policyVersionId(),
                original.saleItemId(), facts.businessDay(), facts.at(), facts.reason(), false);
    }

    /** The reversal source of one menu component in one void: stable, so a redelivery finds it. */
    static UUID componentSource(UUID voidId, UUID componentId) {
        return UUID.nameUUIDFromBytes((voidId + ":" + componentId).getBytes(StandardCharsets.UTF_8));
    }
}
