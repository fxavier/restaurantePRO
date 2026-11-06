package com.xavier.backend.settlement.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * What a payment pays (spec §4 "Alocações impedem cobrar duas vezes"): its target (D69), the order or one of its
 * partitions, for the full amount (a debt payment, S29, may allocate to several debit entries). Σ allocations of a
 * payment = its amount, checked by a deferred constraint trigger. A fact.
 */
@Entity
@Immutable
@Table(schema = "settlement", name = "payment_allocation")
public class PaymentAllocation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 15)
    private TargetType targetType;

    @Column(nullable = false, updatable = false)
    private UUID targetId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentAllocation() {
    }

    /**
     * The whole payment to its target: the order, the partition it paid, or the customer's credit account (whose
     * split over the debits is the customers ledger's, D73).
     */
    public static PaymentAllocation toTarget(Payment payment, Instant now) {
        PaymentAllocation allocation = new PaymentAllocation();
        allocation.paymentId = payment.getId();
        allocation.targetType = payment.getTargetType();
        allocation.targetId = switch (payment.getTargetType()) {
            case PARTITION -> payment.getPartitionId();
            case ORDER -> payment.getOrderId();
            case CREDIT_ACCOUNT -> payment.getCreditAccountId();
        };
        allocation.amount = payment.amount().amount();
        allocation.currency = payment.amount().currency().getCurrencyCode();
        allocation.createdAt = now;
        return allocation;
    }
}
