package com.xavier.backend.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One stock item of a transfer, in its base unit: shipped, then received; {@code unitCost} is the source average at
 * shipping, which the IN and the shortage at the destination use (D120).
 */
@Embeddable
public class TransferLine {

    @Column(nullable = false)
    private UUID stockItemId;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal quantityShipped;

    @Column(precision = 19, scale = 6)
    private BigDecimal quantityReceived;

    @Column(precision = 19, scale = 6)
    private BigDecimal unitCost;

    protected TransferLine() {
        // JPA
    }

    public static TransferLine of(UUID stockItemId, BigDecimal quantity) {
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("a transfer line moves a positive quantity");
        }
        TransferLine line = new TransferLine();
        line.stockItemId = stockItemId;
        line.quantityShipped = quantity.setScale(StockPosition.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        return line;
    }

    void shippedAt(BigDecimal unitCost) {
        this.unitCost = unitCost;
    }

    void received(BigDecimal quantity) {
        this.quantityReceived = quantity.setScale(StockPosition.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    /** What did not arrive: shipped − received. */
    public BigDecimal shortfall() {
        return quantityShipped.subtract(quantityReceived);
    }

    public UUID getStockItemId() {
        return stockItemId;
    }

    public BigDecimal getQuantityShipped() {
        return quantityShipped;
    }

    public BigDecimal getQuantityReceived() {
        return quantityReceived;
    }

    public BigDecimal getUnitCost() {
        return unitCost;
    }
}
