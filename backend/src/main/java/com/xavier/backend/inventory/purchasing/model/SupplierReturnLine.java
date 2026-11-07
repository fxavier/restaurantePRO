package com.xavier.backend.inventory.purchasing.model;

import com.xavier.backend.inventory.model.StockPosition;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/** A line of a supplier return, in the base unit, optionally naming the receipt line it returns. */
@Embeddable
public class SupplierReturnLine {

    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(updatable = false)
    private UUID receiptLineId;

    @Column(nullable = false, updatable = false)
    private UUID stockItemId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal quantityBase;

    protected SupplierReturnLine() {
        // JPA
    }

    public static SupplierReturnLine of(UUID receiptLineId, UUID stockItemId, BigDecimal quantityBase) {
        if (quantityBase.signum() <= 0) {
            throw new IllegalArgumentException("a return line returns a positive quantity");
        }
        SupplierReturnLine line = new SupplierReturnLine();
        line.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        line.receiptLineId = receiptLineId;
        line.stockItemId = stockItemId;
        line.quantityBase = quantityBase.setScale(StockPosition.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        return line;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReceiptLineId() {
        return receiptLineId;
    }

    public UUID getStockItemId() {
        return stockItemId;
    }

    public BigDecimal getQuantityBase() {
        return quantityBase;
    }
}
