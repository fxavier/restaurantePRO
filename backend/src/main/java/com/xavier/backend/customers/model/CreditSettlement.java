package com.xavier.backend.customers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/**
 * Which CREDIT settles which sale DEBIT, and for how much ("CreditSettlement", D73). With a {@code reversalEntryId}
 * it is the opposite fact: that refund DEBIT reopens this much of what the CREDIT settled on the debit. Facts: the
 * database refuses UPDATE and DELETE, and deferred triggers check that a CREDIT (and a refund) is allocated exactly
 * and no debit is settled beyond its amount.
 */
@Entity
@Immutable
@Table(schema = "customers", name = "credit_settlement")
public class CreditSettlement {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID creditEntryId;

    @Column(nullable = false, updatable = false)
    private UUID debitEntryId;

    @Column(updatable = false)
    private UUID reversalEntryId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    protected CreditSettlement() {
        // JPA
    }

    private CreditSettlement(UUID creditEntryId, UUID debitEntryId, UUID reversalEntryId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("A settled amount must be positive");
        }
        this.creditEntryId = Objects.requireNonNull(creditEntryId, "creditEntryId");
        this.debitEntryId = Objects.requireNonNull(debitEntryId, "debitEntryId");
        this.reversalEntryId = reversalEntryId;
        this.amount = amount;
    }

    public static CreditSettlement settles(UUID creditEntryId, UUID debitEntryId, BigDecimal amount) {
        return new CreditSettlement(creditEntryId, debitEntryId, null, amount);
    }

    public static CreditSettlement reopens(UUID creditEntryId, UUID debitEntryId, UUID reversalEntryId,
                                           BigDecimal amount) {
        return new CreditSettlement(creditEntryId, debitEntryId, Objects.requireNonNull(reversalEntryId,
                "reversalEntryId"), amount);
    }

    public UUID getCreditEntryId() {
        return creditEntryId;
    }

    public UUID getDebitEntryId() {
        return debitEntryId;
    }

    public UUID getReversalEntryId() {
        return reversalEntryId;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
