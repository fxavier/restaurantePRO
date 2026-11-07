package com.xavier.backend.inventory.purchasing.model;

import com.xavier.backend.inventory.model.StockPosition;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/**
 * A line of a goods receipt (D125): {@code packQuantity} of the pack (or of the base unit without one), that quantity
 * in the base unit and the cost per base unit at scale 6. Immutable once the receipt is confirmed (a trigger).
 */
@Embeddable
public class GoodsReceiptLine {

    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(updatable = false)
    private UUID purchaseOrderLineId;

    @Column(nullable = false, updatable = false)
    private UUID stockItemId;

    @Column(updatable = false)
    private UUID packId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal packQuantity;

    @Column(nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal quantityBase;

    @Column(nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal unitCostBase;

    protected GoodsReceiptLine() {
        // JPA
    }

    public static GoodsReceiptLine of(UUID purchaseOrderLineId, UUID stockItemId, UUID packId,
                                      BigDecimal packQuantity, BigDecimal quantityBase, BigDecimal unitCostBase) {
        if (packQuantity.signum() <= 0 || quantityBase.signum() <= 0) {
            throw new IllegalArgumentException("a receipt line receives a positive quantity");
        }
        GoodsReceiptLine line = new GoodsReceiptLine();
        line.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        line.purchaseOrderLineId = purchaseOrderLineId;
        line.stockItemId = stockItemId;
        line.packId = packId;
        line.packQuantity = packQuantity.setScale(StockPosition.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        line.quantityBase = quantityBase.setScale(StockPosition.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        line.unitCostBase = unitCostBase.setScale(StockPosition.COST_SCALE, RoundingMode.UNNECESSARY);
        return line;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPurchaseOrderLineId() {
        return purchaseOrderLineId;
    }

    public UUID getStockItemId() {
        return stockItemId;
    }

    public UUID getPackId() {
        return packId;
    }

    public BigDecimal getPackQuantity() {
        return packQuantity;
    }

    public BigDecimal getQuantityBase() {
        return quantityBase;
    }

    public BigDecimal getUnitCostBase() {
        return unitCostBase;
    }
}
