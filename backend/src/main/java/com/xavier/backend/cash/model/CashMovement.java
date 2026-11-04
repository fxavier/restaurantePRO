package com.xavier.backend.cash.model;

import com.xavier.backend.cash.MovementView;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * Money that moved in or out of an OPEN session (spec §8.1, D §4). A fact: the database refuses UPDATE and DELETE
 * (trigger in V8004); a correction is a new movement (R3). A receipt names the settlement Payment it records
 * ({@code source_type = 'PAYMENT'}), and {@code cash_movement_source_uk} keeps it to one movement per payment.
 */
@Entity
@Immutable
@Table(schema = "cash", name = "cash_movement")
public class CashMovement {

    public static final String CASH = "CASH";
    public static final String PAYMENT_SOURCE = "PAYMENT";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID cashDayId;

    @Column(nullable = false, updatable = false)
    private UUID sessionId;

    @Column(nullable = false, updatable = false)
    private UUID registerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private MovementType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 3)
    private MovementDirection direction;

    @Column(nullable = false, updatable = false, length = 30)
    private String methodCode;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(updatable = false)
    private String reason;

    @Column(updatable = false, length = 20)
    private String sourceType;

    @Column(updatable = false)
    private UUID sourceId;

    @Column(nullable = false, updatable = false)
    private UUID actorEmployeeId;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    protected CashMovement() {
        // JPA
    }

    private CashMovement(CashSession session, MovementType type, String methodCode, Money amount, String reason,
                         UUID paymentId, UUID actorEmployeeId, Instant now) {
        session.requireOpen();
        session.requireStorable(amount);
        if (amount.amount().signum() <= 0) {
            throw new CashRuleViolationException(CashRule.INVALID_AMOUNT, "A movement amount must be positive.");
        }
        this.storeId = session.getStoreId();
        this.cashDayId = session.getCashDayId();
        this.sessionId = session.getId();
        this.registerId = session.getRegisterId();
        this.type = type;
        this.direction = type.direction();
        this.methodCode = Objects.requireNonNull(methodCode, "methodCode");
        this.amount = amount.amount();
        this.currency = amount.currency().getCurrencyCode();
        this.reason = reason;
        this.sourceType = paymentId == null ? null : PAYMENT_SOURCE;
        this.sourceId = paymentId;
        this.actorEmployeeId = Objects.requireNonNull(actorEmployeeId, "actorEmployeeId");
        this.occurredAt = now;
    }

    public static CashMovement openingFloat(CashSession session, Instant now) {
        return new CashMovement(session, MovementType.OPENING_FLOAT, CASH, session.getOpeningFloat(), null, null,
                session.getOperatorEmployeeId(), now);
    }

    /** {@code CASH_IN}, {@code CASH_OUT} or {@code EXPENSE}, in cash, with the operator's reason. */
    public static CashMovement manual(CashSession session, MovementType type, Money amount, String reason,
                                      UUID actorEmployeeId, Instant now) {
        if (!MovementType.MANUAL.contains(type)) {
            throw new IllegalArgumentException(type + " is not recorded by hand");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A manual movement needs a reason");
        }
        return new CashMovement(session, type, CASH, amount, reason.strip(), null, actorEmployeeId, now);
    }

    /** What settlement reports for a confirmed Payment (spec §7.5): for its method, cash or not (D52). */
    public static CashMovement receipt(CashSession session, String methodCode, Money amount, UUID paymentId,
                                       UUID actorEmployeeId, Instant now) {
        return new CashMovement(session, MovementType.PAYMENT_RECEIPT, methodCode, amount, null,
                Objects.requireNonNull(paymentId, "paymentId"), actorEmployeeId, now);
    }

    public MovementView toView() {
        return new MovementView(id, sessionId, cashDayId, storeId, type.name(), methodCode,
                Money.of(amount, Currency.getInstance(currency)), sourceId, occurredAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public MovementType getType() {
        return type;
    }
}
