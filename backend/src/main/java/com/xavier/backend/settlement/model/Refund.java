package com.xavier.backend.settlement.model;

import com.xavier.backend.settlement.model.dto.RefundView;
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
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.hibernate.type.SqlTypes;

/**
 * Money given back from a confirmed {@link Payment} (spec §8.3 "Devolução é Refund com referência ao pagamento", D77,
 * P30): to the payment's own method and target, by the refunder in their open cash session, vouched for by another
 * employee. A fact: the database refuses UPDATE and DELETE, and Σ refunds of a payment never exceeds it.
 */
@Entity
@Immutable
@Table(schema = "settlement", name = "refund")
public class Refund {

    static final int MAX_REASON = 500;
    static final int MAX_REFERENCE = 100;

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID paymentId;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 15)
    private TargetType targetType;

    @Column(updatable = false)
    private UUID orderId;

    @Column(updatable = false)
    private UUID partitionId;

    @Column(updatable = false)
    private UUID creditAccountId;

    @Column(nullable = false, updatable = false, length = 30)
    private String methodCode;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false, updatable = false, length = MAX_REASON)
    private String reason;

    @Column(updatable = false, length = MAX_REFERENCE)
    private String externalReference;

    @Column(nullable = false, updatable = false)
    private UUID sessionId;

    @Column(nullable = false, updatable = false)
    private UUID terminalId;

    @Column(nullable = false, updatable = false)
    private LocalDate businessDate;

    @Column(nullable = false, updatable = false)
    private UUID operatorEmployeeId;

    @Column(nullable = false, updatable = false)
    private UUID approverEmployeeId;

    @Column(nullable = false, updatable = false)
    private Instant confirmedAt;

    protected Refund() {
    }

    /**
     * {@code amount} of {@code payment} back to its method and target, recorded in the refunder's {@code sessionId}
     * on {@code businessDate} (that session's cash day). The service checked what is refundable; the database checks
     * the sum again.
     */
    public static Refund of(Payment payment, Money amount, String reason, String externalReference, UUID sessionId,
                            UUID terminalId, LocalDate businessDate, UUID operatorEmployeeId,
                            UUID approverEmployeeId, Instant now) {
        if (amount.amount().signum() <= 0 || !amount.currency().equals(payment.amount().currency())) {
            throw new IllegalArgumentException("A refund is positive and in the payment's currency: " + amount);
        }
        if (Objects.requireNonNull(approverEmployeeId, "approverEmployeeId").equals(operatorEmployeeId)) {
            throw new IllegalArgumentException("The approver of a refund is another employee than the refunder");
        }
        Refund refund = new Refund();
        refund.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        refund.paymentId = payment.getId();
        refund.storeId = payment.getStoreId();
        refund.targetType = payment.getTargetType();
        refund.orderId = payment.getOrderId();
        refund.partitionId = payment.getPartitionId();
        refund.creditAccountId = payment.getCreditAccountId();
        refund.methodCode = payment.getMethodCode();
        refund.amount = amount.amount();
        refund.currency = amount.currency().getCurrencyCode();
        refund.reason = clip(Objects.requireNonNull(reason, "reason"), MAX_REASON);
        refund.externalReference = externalReference == null || externalReference.isBlank() ? null
                : clip(externalReference.strip(), MAX_REFERENCE);
        refund.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        refund.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        refund.businessDate = Objects.requireNonNull(businessDate, "businessDate");
        refund.operatorEmployeeId = Objects.requireNonNull(operatorEmployeeId, "operatorEmployeeId");
        refund.approverEmployeeId = approverEmployeeId;
        refund.confirmedAt = now;
        return refund;
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    public Money amount() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public RefundView toView() {
        return new RefundView(id, paymentId, storeId, targetType.name(), orderId, partitionId, creditAccountId,
                methodCode, amount(), reason, externalReference, sessionId, operatorEmployeeId, approverEmployeeId,
                confirmedAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getPartitionId() {
        return partitionId;
    }

    public UUID getCreditAccountId() {
        return creditAccountId;
    }

    public String getMethodCode() {
        return methodCode;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public UUID getOperatorEmployeeId() {
        return operatorEmployeeId;
    }

    public UUID getApproverEmployeeId() {
        return approverEmployeeId;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public String getReason() {
        return reason;
    }
}
