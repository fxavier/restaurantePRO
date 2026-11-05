package com.xavier.backend.cash.model;

import com.xavier.backend.access.ActorKind;
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
 * (trigger in V8004); a correction is a new {@code ADJUSTMENT} movement on the CLOSED session (D79). A receipt or a
 * refund names the settlement Payment or Refund it records ({@code source_type}), and
 * {@code cash_movement_source_uk} keeps it to one movement per source.
 */
@Entity
@Immutable
@Table(schema = "cash", name = "cash_movement")
public class CashMovement {

    public static final String CASH = "CASH";
    public static final String PAYMENT_SOURCE = "PAYMENT";
    public static final String REFUND_SOURCE = "REFUND";

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

    @Column(updatable = false)
    private UUID actorEmployeeId;

    @Column(updatable = false)
    private UUID actorAccountId;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    protected CashMovement() {
        // JPA
    }

    private CashMovement(CashSession session, MovementType type, MovementDirection direction, String methodCode,
                         Money amount, String reason, String sourceType, UUID sourceId, UUID actorEmployeeId,
                         UUID actorAccountId, Instant now) {
        session.requireStorable(amount);
        if (amount.amount().signum() <= 0) {
            throw new CashRuleViolationException(CashRule.INVALID_AMOUNT, "A movement amount must be positive.");
        }
        if ((actorEmployeeId == null) == (actorAccountId == null)) {
            throw new IllegalArgumentException("A movement has exactly one actor, an employee or an account");
        }
        this.storeId = session.getStoreId();
        this.cashDayId = session.getCashDayId();
        this.sessionId = session.getId();
        this.registerId = session.getRegisterId();
        this.type = type;
        this.direction = direction;
        this.methodCode = Objects.requireNonNull(methodCode, "methodCode");
        this.amount = amount.amount();
        this.currency = amount.currency().getCurrencyCode();
        this.reason = reason;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.actorEmployeeId = actorEmployeeId;
        this.actorAccountId = actorAccountId;
        this.occurredAt = now;
    }

    /** A movement into an OPEN session, by its operator or settlement's collecting employee. */
    private static CashMovement intoOpen(CashSession session, MovementType type, String methodCode, Money amount,
                                         String reason, String sourceType, UUID sourceId, UUID actorEmployeeId,
                                         Instant now) {
        session.requireOpen();
        return new CashMovement(session, type, type.direction(), methodCode, amount, reason, sourceType, sourceId,
                Objects.requireNonNull(actorEmployeeId, "actorEmployeeId"), null, now);
    }

    public static CashMovement openingFloat(CashSession session, Instant now) {
        return intoOpen(session, MovementType.OPENING_FLOAT, CASH, session.getOpeningFloat(), null, null, null,
                session.getOperatorEmployeeId(), now);
    }

    /** {@code CASH_IN}, {@code CASH_OUT} or {@code EXPENSE}, in cash, with the operator's reason. */
    public static CashMovement manual(CashSession session, MovementType type, Money amount, String reason,
                                      UUID actorEmployeeId, Instant now) {
        if (!MovementType.MANUAL.contains(type)) {
            throw new IllegalArgumentException(type + " is not recorded by hand");
        }
        return intoOpen(session, type, CASH, amount, requireReason(reason), null, null, actorEmployeeId, now);
    }

    /** What settlement reports for a confirmed Payment (spec §7.5): for its method, cash or not (D52). */
    public static CashMovement receipt(CashSession session, String methodCode, Money amount, UUID paymentId,
                                       UUID actorEmployeeId, Instant now) {
        return intoOpen(session, MovementType.PAYMENT_RECEIPT, methodCode, amount, null, PAYMENT_SOURCE,
                Objects.requireNonNull(paymentId, "paymentId"), actorEmployeeId, now);
    }

    /** What settlement reports for a confirmed Refund (A23): money out of the refunder's session, in its method. */
    public static CashMovement refund(CashSession session, String methodCode, Money amount, UUID refundId,
                                      UUID actorEmployeeId, Instant now) {
        return intoOpen(session, MovementType.REFUND, methodCode, amount, null, REFUND_SOURCE,
                Objects.requireNonNull(refundId, "refundId"), actorEmployeeId, now);
    }

    /**
     * A later correction on a CLOSED session (D79 (1)): in or out, of any method, with a reason; the stored count is
     * never rewritten (spec §8.3). The caller has checked that the session's day is not FINANCIALLY_CLOSED.
     */
    public static CashMovement adjustment(CashSession session, MovementDirection direction, String methodCode,
                                          Money amount, String reason, UUID actorId, ActorKind actorKind,
                                          Instant now) {
        session.requireClosed();
        Objects.requireNonNull(actorId, "actorId");
        return new CashMovement(session, MovementType.ADJUSTMENT, Objects.requireNonNull(direction, "direction"),
                methodCode, amount, requireReason(reason), null, null,
                actorKind == ActorKind.EMPLOYEE ? actorId : null, actorKind == ActorKind.ACCOUNT ? actorId : null,
                now);
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("This movement needs a reason");
        }
        return reason.strip();
    }

    public MovementView toView() {
        return new MovementView(id, sessionId, cashDayId, storeId, type.name(), methodCode,
                Money.of(amount, Currency.getInstance(currency)), PAYMENT_SOURCE.equals(sourceType) ? sourceId : null,
                occurredAt);
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

    public UUID getSessionId() {
        return sessionId;
    }

    public MovementDirection getDirection() {
        return direction;
    }

    public String getMethodCode() {
        return methodCode;
    }

    public Money getAmount() {
        return Money.of(amount, Currency.getInstance(currency));
    }
}
