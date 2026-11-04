package com.xavier.backend.cash.model;

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
 * The blind count of one method at a session close (spec §8.3: "não reescrever contagem original"). A fact: the
 * database refuses UPDATE and DELETE (trigger in V8003); a later correction is an audited movement (R3).
 */
@Entity
@Immutable
@Table(schema = "cash", name = "cash_session_count")
public class CashSessionCount {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID sessionId;

    @Column(nullable = false, updatable = false, length = 30)
    private String methodCode;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    @Column(nullable = false, updatable = false)
    private Instant countedAt;

    protected CashSessionCount() {
        // JPA
    }

    CashSessionCount(CashSession session, String methodCode, Money amount, Instant countedAt) {
        this.sessionId = session.getId();
        this.methodCode = methodCode;
        this.amount = amount.amount();
        this.currency = amount.currency().getCurrencyCode();
        this.countedAt = countedAt;
    }

    public String getMethodCode() {
        return methodCode;
    }

    public Money getAmount() {
        return Money.of(amount, Currency.getInstance(currency));
    }
}
