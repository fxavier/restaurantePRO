package com.xavier.backend.inventory.purchasing.model;

import com.xavier.backend.inventory.model.StockPosition;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/**
 * A line of a purchase order (D124): {@code quantity} of the purchase unit (the pack, or the base unit without one),
 * that quantity in the base unit, and the cost per base unit (scale 6). Immutable once the order is submitted (a
 * trigger guards the table).
 */
@Embeddable
public class PurchaseOrderLine {

    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID stockItemId;

    @Column(updatable = false)
    private UUID packId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @Column(nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal quantityBase;

    @Column(nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal unitCost;

    protected PurchaseOrderLine() {
        // JPA
    }

    /** @param unitCost per base unit */
    public static PurchaseOrderLine of(UUID stockItemId, UUID packId, BigDecimal quantity, BigDecimal quantityBase,
                                       BigDecimal unitCost) {
        if (quantity.signum() <= 0 || quantityBase.signum() <= 0) {
            throw new IllegalArgumentException("an order line orders a positive quantity");
        }
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        line.stockItemId = stockItemId;
        line.packId = packId;
        line.quantity = quantity.setScale(StockPosition.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        line.quantityBase = quantityBase.setScale(StockPosition.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        line.unitCost = unitCost.setScale(StockPosition.COST_SCALE, RoundingMode.UNNECESSARY);
        return line;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStockItemId() {
        return stockItemId;
    }

    public UUID getPackId() {
        return packId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getQuantityBase() {
        return quantityBase;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }
}
