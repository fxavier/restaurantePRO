package com.xavier.backend.settlement.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * What a payment pays (spec §4 "Alocações impedem cobrar duas vezes"). R2: exactly one allocation per payment, of the
 * full amount, to the order ({@code target_type = 'ORDER'}); R3 adds bill partitions and documents. A fact.
 */
@Entity
@Immutable
@Table(schema = "settlement", name = "payment_allocation")
public class PaymentAllocation {

    static final String ORDER = "ORDER";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID paymentId;

    @Column(nullable = false, updatable = false, length = 10)
    private String targetType;

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

    public static PaymentAllocation toOrder(Payment payment, Instant now) {
        PaymentAllocation allocation = new PaymentAllocation();
        allocation.paymentId = payment.getId();
        allocation.targetType = ORDER;
        allocation.targetId = payment.getOrderId();
        allocation.amount = payment.amount().amount();
        allocation.currency = payment.amount().currency().getCurrencyCode();
        allocation.createdAt = now;
        return allocation;
    }
}
