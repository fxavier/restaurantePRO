package com.xavier.backend.fiscal.model;

import com.xavier.backend.fiscal.model.dto.PaymentSnapshot.PaidAmount;
import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import java.util.Currency;

/** One paid method of a document's payment snapshot (D97), a row of {@code fiscal_document_payment}. */
@Embeddable
public class FiscalDocumentPayment {

    /** 1-based print order. */
    @Column(nullable = false)
    private short position;

    @Column(nullable = false, length = 40)
    private String methodCode;

    @Column(nullable = false, length = 120)
    private String label;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    protected FiscalDocumentPayment() {
        // JPA
    }

    FiscalDocumentPayment(int position, PaidAmount paid) {
        this.position = (short) position;
        this.methodCode = paid.methodCode();
        this.label = paid.label();
        this.amount = paid.amount().amount();
    }

    PaidAmount toView(Currency currency) {
        return new PaidAmount(methodCode, label, Money.of(amount, currency));
    }
}
