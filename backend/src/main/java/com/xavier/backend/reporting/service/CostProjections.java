package com.xavier.backend.reporting.service;

import com.xavier.backend.inventory.CostVarianceFact;
import com.xavier.backend.inventory.InventoryFacts;
import com.xavier.backend.inventory.MovementFact;
import com.xavier.backend.inventory.WasteFact;
import com.xavier.backend.orders.BatchFacts;
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
 * corrections and linked returns (D125). A sale item's family is the one of the release its round line was sold
 * under, found through the round consumption settled the line from.
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
        Map<UUID, Optional<UUID>> releases = new HashMap<>();
        inventory.movementsOf(sourceType, sourceId).ifPresent(set -> set.movements().stream()
                .filter(movement -> COST_TYPES.contains(movement.type()))
                .forEach(movement -> store.insertConsumption(consumption(sourceType, sourceId, movement, releases),
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
        Map<UUID, Optional<UUID>> releases = new HashMap<>();
        for (WasteFact w : inventory.wasteOf(voidId)) {
            store.insertWaste(new WasteRecord(w.voidId(), w.originalMovementId(), w.storeId(), w.businessDate(),
                    w.saleItemId(), familyOf(w.storeId(), w.lineId(), w.saleItemId(), releases), w.lineId(),
                    w.warehouseId(), w.stockItemId(), w.quantity(), w.value()), now);
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
                    line.discountGross().amount()), now);
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
                                          Map<UUID, Optional<UUID>> releases) {
        return new ConsumptionRecord(m.movementId(), m.storeId(), m.businessDate(), m.saleItemId(),
                familyOf(m.storeId(), m.sourceLineId(), m.saleItemId(), releases), m.sourceLineId(), m.warehouseId(),
                m.stockItemId(), m.type(), m.quantity(), m.value(), sourceType, sourceId);
    }

    /** The family of {@code saleItemId} in the release its round line was sold under; null when unknown. */
    private String familyOf(UUID storeId, UUID lineId, UUID saleItemId, Map<UUID, Optional<UUID>> releases) {
        if (lineId == null || saleItemId == null) {
            return null;
        }
        return releases.computeIfAbsent(lineId, this::releaseOf)
                .map(release -> families.familyOf(storeId, release, saleItemId)).orElse(null);
    }

    private Optional<UUID> releaseOf(UUID lineId) {
        return inventory.roundOf(lineId).flatMap(batch -> {
            BatchFacts round = orders.batchFacts(batch);
            return round.lines().stream().filter(line -> line.lineId().equals(lineId)).findFirst()
                    .map(BatchFacts.BatchLineFact::releaseId);
        });
    }
}
