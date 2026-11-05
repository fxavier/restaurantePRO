package com.xavier.backend.settlement.model;

import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
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

    @Column(updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 15)
    private TargetType targetType;

    @Column(updatable = false)
    private UUID partitionId;

    /** The attempt's terminal (R2 rows: {@code null}; their attempt has it). */
    @Column(updatable = false)
    private UUID terminalId;

    /** The business day of the session's cash day (D85; R2 rows: {@code null}). */
    @Column(updatable = false)
    private LocalDate businessDate;

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

    /**
     * The payment of a SUCCEEDED attempt to its target, collected by the employee who created it, into its session,
     * on the business day of the session's cash day.
     */
    public static Payment of(PaymentAttempt attempt, LocalDate businessDate, Instant now) {
        if (attempt.getStatus() != AttemptStatus.SUCCEEDED) {
            throw new IllegalStateException("Only a SUCCEEDED attempt is paid: " + attempt.getId());
        }
        Payment payment = new Payment();
        payment.attemptId = attempt.getId();
        payment.orderId = attempt.getOrderId();
        payment.targetType = attempt.getTargetType();
        payment.partitionId = attempt.getPartitionId();
        payment.terminalId = attempt.getTerminalId();
        payment.businessDate = Objects.requireNonNull(businessDate, "businessDate");
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

    public UUID getAttemptId() {
        return attemptId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public UUID getPartitionId() {
        return partitionId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
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
