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
 * The consumed share of a cost correction (D125): cost that already left stock at the old price, for the margin
 * report (D131). Append-only (a trigger); one per correction that has one.
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

    @Column(nullable = false, updatable = false)
    private UUID correctionId;

    protected CostVariance() {
        // JPA
    }

    public static CostVariance of(UUID storeId, UUID warehouseId, UUID stockItemId, LocalDate businessDate,
                                  BigDecimal value, String currency, UUID correctionId) {
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
        variance.correctionId = correctionId;
        return variance;
    }
}
