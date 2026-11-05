package com.xavier.backend.customers.model;

import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A customer's credit account ("Crédito", CustomerCreditAccount; D73, P38: one per customer, company-wide): the limit
 * and the block. It holds no balance: the balance is the sum of the ledger. Reservations lock this row
 * ({@code FOR UPDATE}), so two terminals cannot both spend the same available credit (spec §8.4).
 */
@Entity
@Table(schema = "customers", name = "credit_account")
public class CreditAccount {

    @Id
    private UUID customerId;

    @Column(name = "credit_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal limit;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false)
    private boolean creditBlocked;

    private String blockedReason;

    private Instant blockedAt;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected CreditAccount() {
        // JPA
    }

    /** A new account with a zero limit, in the company currency. */
    public static CreditAccount open(UUID customerId, Currency currency, Instant now) {
        CreditAccount account = new CreditAccount();
        account.customerId = Objects.requireNonNull(customerId, "customerId");
        account.currency = currency.getCurrencyCode();
        account.limit = Money.of(BigDecimal.ZERO, currency).amount();
        account.createdAt = now;
        return account;
    }

    /** Sets a new limit (≥ 0, in the account currency) and returns the old one. */
    public Money changeLimit(Money newLimit) {
        if (!newLimit.currency().equals(currency())) {
            throw new IllegalArgumentException("A credit limit is in " + currency);
        }
        if (newLimit.amount().signum() < 0) {
            throw new IllegalArgumentException("A credit limit is never negative");
        }
        Money old = limit();
        limit = newLimit.amount();
        return old;
    }

    /** No credit sale while blocked; the debt and the debt payments are unaffected. */
    public void block(String reason, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A credit block needs a reason");
        }
        creditBlocked = true;
        blockedReason = reason.strip();
        blockedAt = now;
    }

    public void unblock() {
        creditBlocked = false;
        blockedReason = null;
        blockedAt = null;
    }

    public Money limit() {
        return Money.of(limit, currency());
    }

    public Currency currency() {
        return Currency.getInstance(currency);
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public boolean isCreditBlocked() {
        return creditBlocked;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public Instant getBlockedAt() {
        return blockedAt;
    }
}
