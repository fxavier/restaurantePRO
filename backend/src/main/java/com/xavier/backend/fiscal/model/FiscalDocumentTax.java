package com.xavier.backend.fiscal.model;

import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/** The tax summary of a document for one rate: Σ of its lines' net and tax (D59, no re-rounding). */
@Entity
@Immutable
@Table(schema = "fiscal", name = "fiscal_document_tax")
public class FiscalDocumentTax {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false, precision = 7, scale = 4)
    private BigDecimal taxRate;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal net;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal tax;

    protected FiscalDocumentTax() {
        // JPA
    }

    FiscalDocumentTax(BigDecimal taxRate, Money net, Money tax) {
        this.taxRate = taxRate;
        this.net = net.amount();
        this.tax = tax.amount();
    }

    DocumentView.TaxSummary toView(Currency currency) {
        return new DocumentView.TaxSummary(taxRate, Money.of(net, currency), Money.of(tax, currency));
    }
}
