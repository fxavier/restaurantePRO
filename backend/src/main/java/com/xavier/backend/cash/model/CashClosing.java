package com.xavier.backend.cash.model;

import com.xavier.backend.access.ActorKind;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.ClosingStaleException;
import com.xavier.backend.cash.exception.IllegalCashStateException;
import com.xavier.backend.cash.model.dto.ClosingView;
import com.xavier.backend.shared.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * The closing of one register for one cash day (spec §8.3, §8.4 "Fecho de caixa considera todas as sessões da
 * caixa"): DRAFT → SUBMITTED → APPROVED | REJECTED. One line per payment method compares what the movements say
 * (expected) with what the operators counted. A rejected closing is kept; the next attempt is a new row with
 * {@code attemptNo + 1} that {@code supersedes} it. Approval is not the day's financial close (a separate command).
 * {@code dayRound} is the number of financial reopens of the day when the closing was drafted (D79 (3)): a new round
 * has its own live closing, and the APPROVED closing of an earlier round is kept.
 */
@Entity
@Table(schema = "cash", name = "cash_closing")
public class CashClosing {

    static final String ENTITY = "CashClosing";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID registerId;

    @Column(nullable = false, updatable = false)
    private UUID cashDayId;

    @Column(nullable = false, updatable = false)
    private int dayRound;

    @Column(nullable = false, updatable = false)
    private int attemptNo;

    @Column(updatable = false)
    private UUID supersedesId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CashClosingStatus status;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false)
    private UUID createdByEmployeeId;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant submittedAt;

    private UUID submittedByEmployeeId;

    private Instant decidedAt;

    private UUID decidedById;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ActorKind decidedByKind;

    private String rejectionReason;

    @OneToMany(mappedBy = "closing", cascade = CascadeType.PERSIST)
    @OrderBy("methodCode")
    private List<CashClosingLine> lines = new ArrayList<>();

    protected CashClosing() {
        // JPA
    }

    /**
     * @param expected per method: Σ IN − Σ OUT of the register's movements on the day
     * @param counted  per method: Σ of the register's session counts on the day (a method not counted is zero)
     */
    public static CashClosing draft(UUID storeId, UUID registerId, UUID cashDayId, int dayRound, int attemptNo,
                                    UUID supersedesId,
                                    Map<String, Money> expected, Map<String, Money> counted, Currency currency,
                                    UUID createdByEmployeeId, Instant now) {
        CashClosing closing = new CashClosing();
        closing.storeId = Objects.requireNonNull(storeId, "storeId");
        closing.registerId = Objects.requireNonNull(registerId, "registerId");
        closing.cashDayId = Objects.requireNonNull(cashDayId, "cashDayId");
        closing.dayRound = dayRound;
        closing.attemptNo = attemptNo;
        closing.supersedesId = supersedesId;
        closing.currency = currency.getCurrencyCode();
        closing.status = CashClosingStatus.DRAFT;
        closing.createdAt = now;
        closing.updatedAt = now;
        closing.createdByEmployeeId = Objects.requireNonNull(createdByEmployeeId, "createdByEmployeeId");
        Money zero = Money.of(BigDecimal.ZERO, currency);
        TreeSet<String> methods = new TreeSet<>(expected.keySet());
        methods.addAll(counted.keySet());
        for (String method : methods) {
            closing.lines.add(new CashClosingLine(closing, method, expected.getOrDefault(method, zero),
                    counted.getOrDefault(method, zero)));
        }
        return closing;
    }

    /** Sets the justification of the named methods' lines (DRAFT only); the closing's version moves on. */
    public void justify(Map<String, String> justifications, Instant now) {
        requireStatus(CashClosingStatus.DRAFT, "be edited");
        justifications.forEach((method, text) -> line(method).justify(text));
        updatedAt = now;
    }

    public void submit(UUID employeeId, Instant now) {
        requireStatus(CashClosingStatus.DRAFT, "be submitted");
        lines.stream().filter(CashClosingLine::needsJustification).findFirst().ifPresent(line -> {
            throw new CashRuleViolationException(CashRule.JUSTIFICATION_REQUIRED,
                    "The " + line.getMethodCode() + " difference of " + line.getDifference().amount()
                            + " needs a justification.");
        });
        status = CashClosingStatus.SUBMITTED;
        submittedAt = now;
        updatedAt = now;
        submittedByEmployeeId = Objects.requireNonNull(employeeId, "employeeId");
    }

    /**
     * SUBMITTED → APPROVED, once the expected amount per method is still what the closing compared (D79 (2)): an
     * adjustment since the draft makes it stale, so it is rejected and the next attempt carries the new amounts.
     *
     * @param expectedNow per method: Σ IN − Σ OUT of the register's movements on the day, re-computed now
     * @throws ClosingStaleException 409 {@code CLOSING_STALE}
     */
    public void approve(Map<String, Money> expectedNow, UUID actorId, ActorKind actorKind, Instant now) {
        requireStatus(CashClosingStatus.SUBMITTED, "be approved");
        requireCurrent(expectedNow);
        decide(CashClosingStatus.APPROVED, actorId, actorKind, now, "be approved");
    }

    /**
     * What the closing compared per method is still what the register should hold (D79 (2)); checked again by the
     * financial close of an APPROVED closing.
     *
     * @throws ClosingStaleException 409 {@code CLOSING_STALE}
     */
    public void requireCurrent(Map<String, Money> expectedNow) {
        TreeSet<String> methods = new TreeSet<>(expectedNow.keySet());
        lines.forEach(line -> methods.add(line.getMethodCode()));
        Money zero = Money.of(BigDecimal.ZERO, Currency.getInstance(currency));
        for (String method : methods) {
            Money compared = lines.stream().filter(l -> l.getMethodCode().equals(method)).findFirst()
                    .map(CashClosingLine::getExpected).orElse(zero);
            if (compared.amount().compareTo(expectedNow.getOrDefault(method, zero).amount()) != 0) {
                throw new ClosingStaleException(id, method);
            }
        }
    }

    public void reject(UUID actorId, ActorKind actorKind, String reason, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A rejection needs a reason");
        }
        decide(CashClosingStatus.REJECTED, actorId, actorKind, now, "be rejected");
        rejectionReason = reason.strip();
    }

    /** Σ |difference| over the lines, in major units: what the closing is off by, whichever direction. */
    public BigDecimal totalAbsoluteDifference() {
        return lines.stream().map(line -> line.getDifference().amount().abs()).reduce(BigDecimal.ZERO,
                BigDecimal::add);
    }

    public ClosingView toView() {
        return new ClosingView(id, storeId, registerId, cashDayId, dayRound, attemptNo, supersedesId, status.name(),
                versionOrZero(), rejectionReason, lines.stream().map(CashClosingLine::toView).toList());
    }

    private void decide(CashClosingStatus decision, UUID actorId, ActorKind actorKind, Instant now, String command) {
        requireStatus(CashClosingStatus.SUBMITTED, command);
        status = decision;
        decidedAt = now;
        updatedAt = now;
        decidedById = Objects.requireNonNull(actorId, "actorId");
        decidedByKind = Objects.requireNonNull(actorKind, "actorKind");
    }

    private CashClosingLine line(String method) {
        return lines.stream().filter(l -> l.getMethodCode().equals(method)).findFirst()
                .orElseThrow(() -> new CashRuleViolationException(CashRule.UNKNOWN_METHOD,
                        "The closing has no line for method " + method + "."));
    }

    private void requireStatus(CashClosingStatus required, String command) {
        if (status != required) {
            throw new IllegalCashStateException(ENTITY, id, status, command);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getRegisterId() {
        return registerId;
    }

    public UUID getCashDayId() {
        return cashDayId;
    }

    public Currency currency() {
        return Currency.getInstance(currency);
    }

    public int getDayRound() {
        return dayRound;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public UUID getSupersedesId() {
        return supersedesId;
    }

    public CashClosingStatus getStatus() {
        return status;
    }

    /** Spring Data reads this to tell a new entity (null) from a stored one: never map null to 0 here. */
    public Long getVersion() {
        return version;
    }

    private long versionOrZero() {
        return version == null ? 0 : version;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public List<CashClosingLine> getLines() {
        return List.copyOf(lines);
    }
}
