package com.xavier.backend.inventory.purchasing.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/**
 * A later price difference on a confirmed receipt line (D125, P51): a fact, never an edit of the receipt. It records
 * the cost before and after, the reason, and how it split ({@link CostCorrection}). Append-only (a trigger).
 */
@Entity
@Table(schema = "inventory", name = "receipt_cost_correction")
public class ReceiptCostCorrection {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID receiptId;

    @Column(nullable = false, updatable = false)
    private UUID receiptLineId;

    @Column(nullable = false, updatable = false)
    private int seq;

    @Column(nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal previousUnitCost;

    @Column(nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal newUnitCost;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal revaluedQuantity;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal revaluationValue;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal varianceValue;

    @Column(nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(nullable = false, updatable = false, length = 10)
    private String actorKind;

    @Column(updatable = false)
    private UUID actorId;

    @Column(nullable = false, updatable = false)
    private Instant at;

    protected ReceiptCostCorrection() {
        // JPA
    }

    public static ReceiptCostCorrection of(UUID receiptId, UUID receiptLineId, int seq, BigDecimal previousUnitCost,
                                           BigDecimal newUnitCost, String reason, CostCorrection split,
                                           LocalDate businessDate, String actorKind, UUID actorId, Instant at) {
        ReceiptCostCorrection correction = new ReceiptCostCorrection();
        correction.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        correction.receiptId = receiptId;
        correction.receiptLineId = receiptLineId;
        correction.seq = seq;
        correction.previousUnitCost = previousUnitCost;
        correction.newUnitCost = newUnitCost;
        correction.reason = reason;
        correction.revaluedQuantity = split.revaluedQuantity();
        correction.revaluationValue = split.revaluationValue();
        correction.varianceValue = split.varianceValue();
        correction.businessDate = businessDate;
        correction.actorKind = actorKind;
        correction.actorId = actorId;
        correction.at = at;
        return correction;
    }

    public UUID getId() {
        return id;
    }

    public UUID getReceiptId() {
        return receiptId;
    }

    public UUID getReceiptLineId() {
        return receiptLineId;
    }

    public int getSeq() {
        return seq;
    }

    public BigDecimal getPreviousUnitCost() {
        return previousUnitCost;
    }

    public BigDecimal getNewUnitCost() {
        return newUnitCost;
    }

    public String getReason() {
        return reason;
    }

    public BigDecimal getRevaluedQuantity() {
        return revaluedQuantity;
    }

    public BigDecimal getRevaluationValue() {
        return revaluationValue;
    }

    public BigDecimal getVarianceValue() {
        return varianceValue;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public Instant getAt() {
        return at;
    }
}
