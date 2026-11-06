package com.xavier.backend.settlement.model;

import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Money received for an order (spec §8.3: CONFIRMED only; a refund is a separate record, R3). A fact: the database
 * refuses UPDATE and DELETE. {@code UNIQUE(attempt_id)}: one attempt never becomes two payments (§7.5).
 */
@Entity
@Immutable
@Table(schema = "settlement", name = "payment")
public class Payment {

    static final String CONFIRMED = "CONFIRMED";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID attemptId;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID sessionId;

    @Column(nullable = false, updatable = false, length = 30)
    private String methodCode;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false, updatable = false, length = 10)
    private String status;

    @Column(nullable = false, updatable = false)
    private UUID collectedByEmployeeId;

    @Column(nullable = false, updatable = false)
    private Instant confirmedAt;

    protected Payment() {
    }

    /** The payment of a SUCCEEDED attempt, collected by the employee who created it, into its session. */
    public static Payment of(PaymentAttempt attempt, Instant now) {
        if (attempt.getStatus() != AttemptStatus.SUCCEEDED) {
            throw new IllegalStateException("Only a SUCCEEDED attempt is paid: " + attempt.getId());
        }
        Payment payment = new Payment();
        payment.attemptId = attempt.getId();
        payment.orderId = attempt.getOrderId();
        payment.storeId = attempt.getStoreId();
        payment.sessionId = attempt.getSessionId();
        payment.methodCode = attempt.getMethodCode();
        payment.amount = attempt.amount().amount();
        payment.currency = attempt.amount().currency().getCurrencyCode();
        payment.status = CONFIRMED;
        payment.collectedByEmployeeId = attempt.getCreatedByEmployeeId();
        payment.confirmedAt = now;
        return payment;
    }

    public Money amount() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getMethodCode() {
        return methodCode;
    }

    public UUID getCollectedByEmployeeId() {
        return collectedByEmployeeId;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
