package com.xavier.backend.cash.model;

import com.xavier.backend.cash.model.dto.ClosingLineView;
import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * One payment method of a {@link CashClosing}: expected, counted, difference = counted − expected (CHECK), and the
 * justification a non-zero difference needs. Only the justification changes, and only while the closing is a DRAFT
 * (trigger in V8005).
 */
@Entity
@Table(schema = "cash", name = "cash_closing_line")
public class CashClosingLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "closing_id", nullable = false, updatable = false)
    private CashClosing closing;

    @Column(nullable = false, updatable = false, length = 30)
    private String methodCode;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal expectedAmount;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal countedAmount;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal differenceAmount;

    @Column(nullable = false, updatable = false, length = 3)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;

    private String justification;

    protected CashClosingLine() {
        // JPA
    }

    CashClosingLine(CashClosing closing, String methodCode, Money expected, Money counted) {
        this.closing = closing;
        this.methodCode = methodCode;
        this.expectedAmount = expected.amount();
        this.countedAmount = counted.amount();
        this.differenceAmount = counted.minus(expected).amount();
        this.currency = expected.currency().getCurrencyCode();
    }

    void justify(String text) {
        justification = text == null || text.isBlank() ? null : text.strip();
    }

    boolean needsJustification() {
        return differenceAmount.signum() != 0 && justification == null;
    }

    ClosingLineView toView() {
        return new ClosingLineView(methodCode, getExpected(), getCounted(), getDifference(), justification);
    }

    public String getMethodCode() {
        return methodCode;
    }

    public Money getExpected() {
        return money(expectedAmount);
    }

    public Money getCounted() {
        return money(countedAmount);
    }

    public Money getDifference() {
        return money(differenceAmount);
    }

    public String getJustification() {
        return justification;
    }

    private Money money(BigDecimal amount) {
        return Money.of(amount, Currency.getInstance(currency));
    }
}
