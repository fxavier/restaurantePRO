package com.xavier.backend.settlement.model;

import com.xavier.backend.settlement.exception.SettlementConflictException;
import com.xavier.backend.settlement.exception.SettlementRule;
import com.xavier.backend.settlement.exception.SettlementRuleException;
import com.xavier.backend.settlement.model.dto.AttemptView;
import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.hibernate.type.SqlTypes;

/**
 * One try to collect money for an order (spec §7.3–§7.5, §8.3; r2-decisions §4 settlement). Every transition is a
 * method; an illegal one throws {@code ILLEGAL_ATTEMPT_STATE} (409) and changes nothing.
 *
 * <ul>
 *   <li>CASH is born SUCCEEDED, with {@code change = tendered − amount}.</li>
 *   <li>EXTERNAL_TERMINAL is born PENDING; the operator confirms (SUCCEEDED) or cancels it.</li>
 *   <li>INTEGRATED is born CREATED with its provider and reference persisted before the provider is called (§7.3);
 *       the submitter moves it to PENDING (exactly once) and records the provider's outcome. A timeout is UNKNOWN,
 *       which only a resolve (provider query, or an approved reclassification) turns into SUCCEEDED or FAILED.</li>
 * </ul>
 *
 * The id is assigned here (UUIDv7, D7) because an integrated attempt sends it to the provider as its idempotency
 * reference (D53) in the row's first insert.
 */
@Entity
@Table(schema = "settlement", name = "payment_attempt")
public class PaymentAttempt {

    static final int MAX_TEXT = 500;

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID terminalId;

    @Column(nullable = false, updatable = false)
    private UUID sessionId;

    @Column(nullable = false, updatable = false, length = 30)
    private String methodCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private PaymentMethodKind methodKind;

    @Column(updatable = false, length = 50)
    private String providerCode;

    @Column(updatable = false, length = 100)
    private String providerReference;

    @Column(length = 100)
    private String providerTransactionRef;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(updatable = false, precision = 19, scale = 4)
    private BigDecimal tendered;

    @Column(updatable = false, precision = 19, scale = 4)
    private BigDecimal changeGiven;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AttemptStatus status;

    @Column(length = 50)
    private String authorizationCode;

    @Column(length = MAX_TEXT)
    private String outcomeDetail;

    @Column(nullable = false, updatable = false)
    private UUID createdByEmployeeId;

    private UUID resolvedByEmployeeId;

    private UUID approverEmployeeId;

    @Column(length = MAX_TEXT)
    private String resolutionReason;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected PaymentAttempt() {
    }

    /**
     * @param tendered what the customer handed over, for cash only ({@code null} otherwise)
     * @throws SettlementRuleException {@code INVALID_AMOUNT}, {@code TENDERED_BELOW_AMOUNT} or
     *         {@code CHANGE_FOR_CASH_ONLY}
     */
    public static PaymentAttempt create(UUID orderId, UUID storeId, UUID terminalId, UUID sessionId,
                                        PaymentMethod method, Money amount, Money tendered, UUID employeeId,
                                        Instant now) {
        Objects.requireNonNull(amount, "amount");
        if (amount.amount().signum() <= 0) {
            throw new SettlementRuleException(SettlementRule.INVALID_AMOUNT, "A payment amount must be positive.");
        }
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        attempt.orderId = Objects.requireNonNull(orderId, "orderId");
        attempt.storeId = Objects.requireNonNull(storeId, "storeId");
        attempt.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        attempt.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        attempt.methodCode = method.getCode();
        attempt.methodKind = method.getKind();
        attempt.amount = amount.amount();
        attempt.currency = amount.currency().getCurrencyCode();
        attempt.createdByEmployeeId = Objects.requireNonNull(employeeId, "employeeId");
        attempt.createdAt = now;
        attempt.updatedAt = now;
        switch (method.getKind()) {
            case CASH -> attempt.tenderCash(amount, tendered);
            case EXTERNAL_TERMINAL -> {
                requireNoChange(tendered);
                attempt.status = AttemptStatus.PENDING;
            }
            case INTEGRATED -> {
                requireNoChange(tendered);
                attempt.status = AttemptStatus.CREATED;
                attempt.providerCode = method.getProviderCode();
                attempt.providerReference = attempt.id.toString();
            }
        }
        return attempt;
    }

    private void tenderCash(Money paid, Money handedOver) {
        if (handedOver != null && !handedOver.currency().equals(paid.currency())) {
            throw new SettlementRuleException(SettlementRule.INVALID_AMOUNT,
                    "The tendered amount must be in " + paid.currency().getCurrencyCode() + ".");
        }
        if (handedOver == null || handedOver.amount().compareTo(paid.amount()) < 0) {
            throw new SettlementRuleException(SettlementRule.TENDERED_BELOW_AMOUNT,
                    "The tendered amount must be at least the amount paid.");
        }
        tendered = handedOver.amount();
        changeGiven = handedOver.minus(paid).amount();
        status = AttemptStatus.SUCCEEDED;
    }

    private static void requireNoChange(Money tendered) {
        if (tendered != null) {
            throw new SettlementRuleException(SettlementRule.CHANGE_FOR_CASH_ONLY,
                    "A tendered amount and change are for cash only.");
        }
    }

    /** D48 tx-a: CREATED → PENDING, right before the provider is called. */
    public void submit(Instant now) {
        move(Set.of(AttemptStatus.CREATED), AttemptStatus.PENDING, "be submitted", now);
    }

    /** D50 §7.5: the operator confirms what the external terminal approved. No card data is kept. */
    public void confirm(String authorizationCode, Instant now) {
        requireKind(PaymentMethodKind.EXTERNAL_TERMINAL, "be confirmed by the operator");
        move(Set.of(AttemptStatus.PENDING), AttemptStatus.SUCCEEDED, "be confirmed", now);
        this.authorizationCode = authorizationCode;
    }

    /** CREATED, or PENDING when no provider was called, → CANCELLED. */
    public void cancel(String reason, UUID employeeId, Instant now) {
        Set<AttemptStatus> cancellable = methodKind == PaymentMethodKind.INTEGRATED
                ? Set.of(AttemptStatus.CREATED) : Set.of(AttemptStatus.CREATED, AttemptStatus.PENDING);
        move(cancellable, AttemptStatus.CANCELLED, "be cancelled", now);
        resolvedByEmployeeId = employeeId;
        resolutionReason = clip(reason);
    }

    /** D48 tx-b: the provider charged it. */
    public void recordProviderSuccess(String providerTransactionRef, Instant now) {
        move(Set.of(AttemptStatus.PENDING), AttemptStatus.SUCCEEDED, "record a provider success", now);
        this.providerTransactionRef = providerTransactionRef;
    }

    /** D48 tx-b: the provider declined it. */
    public void recordProviderFailure(String reason, Instant now) {
        move(Set.of(AttemptStatus.PENDING), AttemptStatus.FAILED, "record a provider failure", now);
        outcomeDetail = clip(reason);
    }

    /** D48 tx-b or the sweeper: the outcome is not known (timeout, crash during the call). Never FAILED. */
    public void markUnknown(String detail, Instant now) {
        move(Set.of(AttemptStatus.PENDING), AttemptStatus.UNKNOWN, "become unknown", now);
        outcomeDetail = clip(detail);
    }

    /**
     * D50 §7.4: the provider's answer to a status query. A success settles an UNKNOWN or PENDING attempt. A failure
     * settles only an UNKNOWN one: while PENDING the charge may still be in flight (the provider may not know the
     * reference yet), so "failed" is not final for it.
     */
    public void resolveFromProvider(boolean succeeded, String providerTransactionRef, String reason,
                                    UUID employeeId, Instant now) {
        requireKind(PaymentMethodKind.INTEGRATED, "be resolved by a provider query");
        move(succeeded ? Set.of(AttemptStatus.UNKNOWN, AttemptStatus.PENDING) : Set.of(AttemptStatus.UNKNOWN),
                succeeded ? AttemptStatus.SUCCEEDED : AttemptStatus.FAILED, "be resolved", now);
        resolvedByEmployeeId = employeeId;
        if (succeeded) {
            this.providerTransactionRef = providerTransactionRef;
        } else {
            outcomeDetail = clip(reason);
        }
    }

    /**
     * D50 §7.4: an authorized manual reclassification of an UNKNOWN attempt, with an approver and a reason.
     *
     * @throws SettlementRuleException {@code REASON_REQUIRED} or {@code INVALID_OUTCOME}
     */
    public void reclassify(AttemptStatus outcome, String reason, UUID employeeId, UUID approverId, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new SettlementRuleException(SettlementRule.REASON_REQUIRED,
                    "A reclassification needs a reason.");
        }
        if (outcome != AttemptStatus.SUCCEEDED && outcome != AttemptStatus.FAILED) {
            throw new SettlementRuleException(SettlementRule.INVALID_OUTCOME,
                    "An attempt is reclassified as SUCCEEDED or FAILED only.");
        }
        move(Set.of(AttemptStatus.UNKNOWN), outcome, "be reclassified", now);
        resolvedByEmployeeId = Objects.requireNonNull(employeeId, "employeeId");
        approverEmployeeId = Objects.requireNonNull(approverId, "approverId");
        resolutionReason = clip(reason);
    }

    private void move(Set<AttemptStatus> from, AttemptStatus to, String command, Instant now) {
        if (!from.contains(status)) {
            throw SettlementConflictException.illegalState(id, status, command);
        }
        status = to;
        updatedAt = now;
    }

    private void requireKind(PaymentMethodKind kind, String command) {
        if (methodKind != kind) {
            throw SettlementConflictException.illegalState(id, status, command + " (it is " + methodKind + ")");
        }
    }

    private static String clip(String text) {
        return text == null || text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT);
    }

    public Money amount() {
        return Money.of(amount, Currency.getInstance(currency));
    }

    public Money change() {
        return changeGiven == null ? null : Money.of(changeGiven, Currency.getInstance(currency));
    }

    public AttemptView toView() {
        Currency money = Currency.getInstance(currency);
        return new AttemptView(id, orderId, storeId, sessionId, methodCode, methodKind.name(), status.name(),
                amount(), tendered == null ? null : Money.of(tendered, money), change(), authorizationCode,
                outcomeDetail, resolutionReason, createdAt, updatedAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getTerminalId() {
        return terminalId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getMethodCode() {
        return methodCode;
    }

    public PaymentMethodKind getMethodKind() {
        return methodKind;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public UUID getCreatedByEmployeeId() {
        return createdByEmployeeId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
