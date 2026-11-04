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

/** A copied order-line snapshot (D59). Written once with its draft; immutable once the document is issued. */
@Entity
@Immutable
@Table(schema = "fiscal", name = "fiscal_document_line")
public class FiscalDocumentLine {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private int lineNo;

    @Column(updatable = false)
    private UUID sourceLineId;

    @Column(nullable = false, updatable = false, length = 200)
    private String description;

    @Column(nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(nullable = false, updatable = false, precision = 7, scale = 4)
    private BigDecimal taxRate;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal gross;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal net;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal tax;

    protected FiscalDocumentLine() {
        // JPA
    }

    FiscalDocumentLine(int lineNo, UUID sourceLineId, String description, BigDecimal quantity, Money unitPrice,
                       BigDecimal taxRate, Money gross, Money net, Money tax) {
        this.lineNo = lineNo;
        this.sourceLineId = sourceLineId;
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice.amount();
        this.taxRate = taxRate;
        this.gross = gross.amount();
        this.net = net.amount();
        this.tax = tax.amount();
    }

    DocumentView.Line toView(Currency currency) {
        return new DocumentView.Line(lineNo, sourceLineId, description, quantity, Money.of(unitPrice, currency),
                taxRate, Money.of(gross, currency), Money.of(net, currency), Money.of(tax, currency));
    }
}
