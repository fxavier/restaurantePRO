package com.xavier.backend.cash.model;

import com.xavier.backend.cash.MethodCount;
import com.xavier.backend.cash.OpenSessionView;
import com.xavier.backend.cash.SessionView;
import com.xavier.backend.cash.exception.CashRule;
import com.xavier.backend.cash.exception.CashRuleViolationException;
import com.xavier.backend.cash.exception.IllegalCashStateException;
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
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * A direct cash session (P16): one operator on one register within one cash day, OPEN → CLOSED (spec §8.3). At most
 * one OPEN session per register and per operator in a store (partial unique indexes in V8003), so a payment routes
 * to exactly one session. The close records the blind count per method as immutable {@link CashSessionCount}s.
 */
@Entity
@Table(schema = "cash", name = "cash_session")
public class CashSession {

    static final String ENTITY = "CashSession";
    static final int MAX_INTEGER_DIGITS = 15;

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
    private UUID operatorEmployeeId;

    @Column(nullable = false, updatable = false)
    private UUID terminalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CashSessionStatus status;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal openingFloat;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false, updatable = false)
    private Instant openedAt;

    private Instant closedAt;

    private UUID closedByEmployeeId;

    @Version
    private Long version;

    protected CashSession() {
        // JPA
    }

    public static CashSession open(UUID storeId, UUID registerId, UUID cashDayId, UUID operatorEmployeeId,
                                   UUID terminalId, Money openingFloat, Instant now) {
        if (openingFloat.amount().signum() < 0) {
            throw new CashRuleViolationException(CashRule.INVALID_AMOUNT, "The opening float cannot be negative.");
        }
        requireFitsColumns(openingFloat);
        CashSession session = new CashSession();
        session.storeId = Objects.requireNonNull(storeId, "storeId");
        session.registerId = Objects.requireNonNull(registerId, "registerId");
        session.cashDayId = Objects.requireNonNull(cashDayId, "cashDayId");
        session.operatorEmployeeId = Objects.requireNonNull(operatorEmployeeId, "operatorEmployeeId");
        session.terminalId = Objects.requireNonNull(terminalId, "terminalId");
        session.openingFloat = openingFloat.amount();
        session.currency = openingFloat.currency().getCurrencyCode();
        session.status = CashSessionStatus.OPEN;
        session.openedAt = now;
        return session;
    }

    /** Money moves only into an OPEN session. */
    public void requireOpen() {
        if (status != CashSessionStatus.OPEN) {
            throw new CashRuleViolationException(CashRule.SESSION_NOT_OPEN, "The cash session " + id + " is closed.");
        }
    }

    /**
     * OPEN → CLOSED with the operator's blind count. A method not counted counts as zero in the closing.
     *
     * @return one immutable count per method, to be saved with the session
     */
    public List<CashSessionCount> close(List<MethodCount> counts, UUID closedByEmployeeId, Instant now) {
        if (status != CashSessionStatus.OPEN) {
            throw new IllegalCashStateException(ENTITY, id, status, "close");
        }
        Set<String> methods = new HashSet<>();
        List<CashSessionCount> recorded = new ArrayList<>();
        for (MethodCount count : counts) {
            requireCountable(count.amount());
            if (!methods.add(count.methodCode())) {
                throw new CashRuleViolationException(CashRule.DUPLICATE_METHOD,
                        "The method " + count.methodCode() + " is counted twice.");
            }
            recorded.add(new CashSessionCount(this, count.methodCode(), count.amount(), now));
        }
        status = CashSessionStatus.CLOSED;
        closedAt = now;
        this.closedByEmployeeId = Objects.requireNonNull(closedByEmployeeId, "closedByEmployeeId");
        return List.copyOf(recorded);
    }

    /**
     * The amount is in this session's currency (single currency per installation, spec §8.4) and fits the cash
     * amount columns ({@code NUMERIC(19, 4)}).
     */
    public void requireStorable(Money amount) {
        if (!amount.currency().equals(currency())) {
            throw new CashRuleViolationException(CashRule.CURRENCY_MISMATCH,
                    amount.currency() + " is not the store currency " + currency + ".");
        }
        requireFitsColumns(amount);
    }

    static void requireFitsColumns(Money amount) {
        if (amount.amount().precision() - amount.amount().scale() > MAX_INTEGER_DIGITS) {
            throw new CashRuleViolationException(CashRule.INVALID_AMOUNT,
                    "An amount has at most " + MAX_INTEGER_DIGITS + " integer digits.");
        }
    }

    private void requireCountable(Money amount) {
        requireStorable(amount);
        if (amount.amount().signum() < 0) {
            throw new CashRuleViolationException(CashRule.INVALID_AMOUNT, "A count cannot be negative.");
        }
    }

    public SessionView toView() {
        return new SessionView(id, storeId, registerId, cashDayId, operatorEmployeeId, status.name(),
                versionOrZero());
    }

    public OpenSessionView toOpenView() {
        return new OpenSessionView(id, storeId, registerId, cashDayId, operatorEmployeeId);
    }

    public Currency currency() {
        return Currency.getInstance(currency);
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

    public UUID getOperatorEmployeeId() {
        return operatorEmployeeId;
    }

    public CashSessionStatus getStatus() {
        return status;
    }

    public Money getOpeningFloat() {
        return Money.of(openingFloat, currency());
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    /** Spring Data reads this to tell a new entity (null) from a stored one: never map null to 0 here. */
    public Long getVersion() {
        return version;
    }

    private long versionOrZero() {
        return version == null ? 0 : version;
    }
}
