package com.xavier.backend.inventory.purchasing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A cost difference of a receipt line that the stock value cannot carry (D125 amended), for the margin report (D131):
 * the consumed share of a cost correction (cost that already left stock at the old price), or a linked supplier
 * return's credit against the average it left at. {@code value} is signed: positive is always more cost of goods,
 * negative less. Append-only (a trigger); at most one per correction or return line.
 */
@Entity
@Table(schema = "inventory", name = "cost_variance")
public class CostVariance {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(nullable = false, updatable = false)
    private UUID stockItemId;

    @Column(nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal value;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(updatable = false)
    private UUID correctionId;

    @Column(updatable = false)
    private UUID returnLineId;

    @Column(updatable = false)
    private UUID receiptLineId;

    protected CostVariance() {
        // JPA
    }

    /** The consumed share of a cost correction of {@code receiptLineId}. */
    public static CostVariance ofCorrection(UUID storeId, UUID warehouseId, UUID stockItemId, LocalDate businessDate,
                                            BigDecimal value, String currency, UUID correctionId,
                                            UUID receiptLineId) {
        CostVariance variance = of(storeId, warehouseId, stockItemId, businessDate, value, currency);
        variance.correctionId = correctionId;
        variance.receiptLineId = receiptLineId;
        return variance;
    }

    /**
     * A return of {@code receiptLineId} left at the average while the supplier credits the line's cost: their
     * difference, {@code (average − receipt cost) × quantity} (negative when the credit exceeds the average).
     */
    public static CostVariance ofReturn(UUID storeId, UUID warehouseId, UUID stockItemId, LocalDate businessDate,
                                        BigDecimal value, String currency, UUID returnLineId, UUID receiptLineId) {
        CostVariance variance = of(storeId, warehouseId, stockItemId, businessDate, value, currency);
        variance.returnLineId = returnLineId;
        variance.receiptLineId = receiptLineId;
        return variance;
    }

    private static CostVariance of(UUID storeId, UUID warehouseId, UUID stockItemId, LocalDate businessDate,
                                   BigDecimal value, String currency) {
        if (value.signum() == 0) {
            throw new IllegalArgumentException("a cost variance moves a value");
        }
        CostVariance variance = new CostVariance();
        variance.storeId = storeId;
        variance.warehouseId = warehouseId;
        variance.stockItemId = stockItemId;
        variance.businessDate = businessDate;
        variance.value = value;
        variance.currency = currency;
        return variance;
    }
}
