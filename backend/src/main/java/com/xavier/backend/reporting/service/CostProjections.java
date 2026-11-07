package com.xavier.backend.reporting.service;

import com.xavier.backend.inventory.CostVarianceFact;
import com.xavier.backend.inventory.InventoryFacts;
import com.xavier.backend.inventory.MovementFact;
import com.xavier.backend.inventory.WasteFact;
import com.xavier.backend.orders.BatchFacts.BatchLineFact;
import com.xavier.backend.orders.DiscountFacts;
import com.xavier.backend.orders.DiscountFacts.DiscountLine;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.reporting.repository.ProjectionStore;
import com.xavier.backend.reporting.repository.ProjectionStore.ConsumptionRecord;
import com.xavier.backend.reporting.repository.ProjectionStore.DiscountRecord;
import com.xavier.backend.reporting.repository.ProjectionStore.VarianceRecord;
import com.xavier.backend.reporting.repository.ProjectionStore.WasteRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Projects the cost of goods and the discounts (D131, the D84 pattern): pulls one source's immutable facts through
 * inventory's and orders' read-only facades and inserts its rows keyed by the source, so a redelivery changes
 * nothing. The live listeners ({@code reporting.inventory}) and the rebuild both come through here.
 *
 * <p>Cost of goods: the movements that are cost (a sale's consumption, its reversal by a void without waste, a manual
 * waste), each wasted void's share of a sale's cost (no stock moved, D117 as amended), and the cost variances of
 * corrections and linked returns (D125). Cost belongs to the item sold on the round line it comes from (for a menu,
 * the menu, not its components) and to that item's family in the release the line was sold under; the round is the
 * one consumption settled the line from. A cost with no round line (a manual waste) has no item.
 */
@Service
public class CostProjections {

    /** The movement types that are cost of goods; every other movement only moves stock or value between places. */
    static final Set<String> COST_TYPES = Set.of("SALE_CONSUMPTION", "CONSUMPTION_REVERSAL", "WASTE");

    private final InventoryFacts inventory;
    private final OrdersApi orders;
    private final ReleaseFamilies families;
    private final ProjectionStore store;
    private final Clock clock;

    CostProjections(InventoryFacts inventory, OrdersApi orders, ReleaseFamilies families, ProjectionStore store,
                    Clock clock) {
        this.inventory = inventory;
        this.orders = orders;
        this.families = families;
        this.store = store;
        this.clock = clock;
    }

    /** The cost movements and the cost variances of one source that posted stock movements (or a variance). */
    @Transactional
    public void projectStockSource(String sourceType, UUID sourceId) {
        Instant now = clock.instant();
        Map<UUID, Optional<BatchLineFact>> sold = new HashMap<>();
        inventory.movementsOf(sourceType, sourceId).ifPresent(set -> set.movements().stream()
                .filter(movement -> COST_TYPES.contains(movement.type()))
                .forEach(movement -> store.insertConsumption(consumption(sourceType, sourceId, movement, sold),
                        now)));
        for (CostVarianceFact v : inventory.costVariancesOf(sourceType, sourceId)) {
            store.insertVariance(new VarianceRecord(v.varianceId(), v.sourceType(), v.sourceId(), v.storeId(),
                    v.businessDate(), v.warehouseId(), v.stockItemId(), v.receiptLineId(), v.value()), now);
        }
    }

    /** The waste records of one wasted void. */
    @Transactional
    public void projectWaste(UUID voidId) {
        Instant now = clock.instant();
        Map<UUID, Optional<BatchLineFact>> sold = new HashMap<>();
        for (WasteFact w : inventory.wasteOf(voidId)) {
            Sold item = soldOn(w.storeId(), w.lineId(), w.saleItemId(), sold);
            store.insertWaste(new WasteRecord(w.voidId(), w.originalMovementId(), w.storeId(), w.businessDate(),
                    item.itemId(), item.familyCode(), w.lineId(), w.warehouseId(), w.stockItemId(), w.quantity(),
                    w.value()), now);
        }
    }

    /** The discounted lines of one discount source (a round's promotions, or a price adjustment). */
    @Transactional
    public void projectDiscounts(String sourceType, UUID sourceId) {
        DiscountFacts facts = orders.discountFacts(sourceType, sourceId);
        Instant now = clock.instant();
        for (DiscountLine line : facts.lines()) {
            store.insertDiscount(new DiscountRecord(facts.sourceType(), facts.sourceId(), line.lineId(),
                    facts.storeId(), facts.businessDay(), line.itemId(),
                    families.familyOf(facts.storeId(), line.releaseId(), line.itemId()), line.origin(), kindOf(line),
                    line.promotionCode(), line.operatorEmployeeId(), line.approverEmployeeId(), line.quantity(),
                    line.discountGross().amount(), line.consumedLineId(), line.consumedLineQuantity()), now);
        }
    }

    /** PROMOTION, OFFER and LOYALTY (a REWARD) name their kind; a MANUAL change is a DISCOUNT or its REVERSAL. */
    static String kindOf(DiscountLine line) {
        return switch (line.origin()) {
            case "PROMOTION", "OFFER" -> line.origin();
            case "LOYALTY" -> "REWARD";
            default -> line.discountGross().amount().signum() < 0 ? "REVERSAL" : "DISCOUNT";
        };
    }

    private ConsumptionRecord consumption(String sourceType, UUID sourceId, MovementFact m,
                                          Map<UUID, Optional<BatchLineFact>> sold) {
        Sold item = soldOn(m.storeId(), m.sourceLineId(), m.saleItemId(), sold);
        return new ConsumptionRecord(m.movementId(), m.storeId(), m.businessDate(), item.itemId(), item.familyCode(),
                m.sourceLineId(), m.warehouseId(), m.stockItemId(), m.type(), m.quantity(), m.value(), sourceType,
                sourceId);
    }

    /**
     * The item sold on round line {@code lineId} and its family in the release it was sold under. Without the round
     * line (no line, or not settled by consumption) the movement's own item stands, without a family.
     */
    private Sold soldOn(UUID storeId, UUID lineId, UUID movementItemId, Map<UUID, Optional<BatchLineFact>> sold) {
        if (lineId == null) {
            return new Sold(movementItemId, null);
        }
        return sold.computeIfAbsent(lineId, this::roundLine)
                .map(line -> new Sold(line.itemId(), families.familyOf(storeId, line.releaseId(), line.itemId())))
                .orElse(new Sold(movementItemId, null));
    }

    private Optional<BatchLineFact> roundLine(UUID lineId) {
        return inventory.roundOf(lineId).flatMap(batch -> orders.batchFacts(batch).lines().stream()
                .filter(line -> line.lineId().equals(lineId)).findFirst());
    }

    private record Sold(UUID itemId, String familyCode) {
    }
}
