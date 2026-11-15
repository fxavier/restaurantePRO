package com.xavier.backend.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One stock item of a transfer, in its base unit: shipped, then received; {@code unitCost} is the source average at
 * shipping, which the IN and the shortage at the destination use (D120), and {@code valueShipped} the value its OUT
 * took, which the IN carries (D113 as amended). Both are kept here because the destination cannot read the source
 * store's ledger under row-level security (D168); it sees the transfer and its lines.
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

    @Column(precision = 19, scale = 4)
    private BigDecimal valueShipped;

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

    void shipped(Shipped out) {
        this.unitCost = out.unitCost();
        this.valueShipped = out.value();
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

    public BigDecimal getValueShipped() {
        return valueShipped;
    }

    /** What a line's TRANSFER_OUT recorded: its unit cost and the (positive) value it took from the source. */
    public record Shipped(BigDecimal unitCost, BigDecimal value) {
    }
}
