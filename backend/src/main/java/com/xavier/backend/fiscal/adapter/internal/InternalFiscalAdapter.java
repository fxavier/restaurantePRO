package com.xavier.backend.fiscal.adapter.internal;

import com.xavier.backend.fiscal.FiscalAdapter;
import com.xavier.backend.fiscal.IssueRequest;
import com.xavier.backend.fiscal.IssueResult;
import com.xavier.backend.fiscal.IssuedDocument;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot;
import com.xavier.backend.fiscal.model.dto.PaymentStatus;
import com.xavier.backend.fiscal.model.event.DocumentPrintout;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The generic, NON-CERTIFIED adapter (B4, P7): NOT FOR PRODUCTION. It issues locally and chains a SHA-256 over
 * (previous hash of the series, type, series, number, date, total) into {@code adapter_data}, so tampering is
 * detectable. That chain is an integrity aid, not a certification. It prints only the company fields R1 holds (P21).
 */
@Component
public class InternalFiscalAdapter implements FiscalAdapter {

    public static final String CODE = "internal";
    public static final String ON_ACCOUNT = "A CRÉDITO";
    static final String CREDIT_LABEL = "A crédito";
    static final String OFFER = "Oferta";
    /** U+2212, as the PRD writes it; a printer table without it prints "-" (BUG-0001, {@code CodePage}). */
    static final String MINUS = "\u2212";

    private static final DateTimeFormatter PRINTED_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public boolean certified() {
        return false;
    }

    @Override
    public IssueResult issue(IssueRequest request) {
        String chained = String.join("|", request.previousAdapterData() == null ? "" : request.previousAdapterData(),
                request.documentType(), request.seriesCode(), Long.toString(request.number()),
                request.issuedAt().toString(), request.totalGross().amount().toPlainString(),
                request.totalGross().currency().getCurrencyCode());
        return new IssueResult(sha256(chained), null);
    }

    @Override
    public DocumentPrintout printout(IssuedDocument document, boolean copy) {
        List<String> lines = new ArrayList<>();
        lines.add(document.companyName());
        lines.add("Contribuinte: " + document.companyTaxId());
        lines.add(document.storeName());
        lines.add("Documento de venda " + document.seriesCode() + "/" + document.number());
        lines.add("Data: " + PRINTED_DATE.format(document.issuedAt().atZone(document.storeZone())));
        if (copy) {
            lines.add("CÓPIA");
        }
        if (wholeOnAccount(document)) {
            lines.add(ON_ACCOUNT); // the whole document is a credit sale (A20, D97 rule 5)
        }
        Buyer buyer = document.buyer();
        if (buyer != null) {
            addIfPresent(lines, "Cliente: ", buyer.name());
            addIfPresent(lines, "Contribuinte do cliente: ", buyer.taxId());
            addIfPresent(lines, "Morada: ", buyer.address());
        }
        lines.add("--------------------------------");
        document.lines().forEach(line -> addLine(lines, line));
        lines.add("--------------------------------");
        for (DocumentView.TaxSummary tax : document.taxes()) {
            lines.add("Taxa " + plain(tax.rate()) + "%: base " + tax.net().amount().toPlainString() + " imposto "
                    + tax.tax().amount().toPlainString());
        }
        lines.add("TOTAL " + document.totalGross().amount().toPlainString() + " "
                + document.totalGross().currency().getCurrencyCode());
        if (document.payments() != null) {
            addPayments(lines, document.payments());
        }
        return new DocumentPrintout(lines, document.qrPayload());
    }

    /**
     * D101/D107: a discounted line prints at its list price, then its discount (list gross − gross) and its value; an
     * offered line (0.00) says "Oferta" instead. A line without a list price, or at it, prints as before R4.
     */
    private static void addLine(List<String> lines, DocumentView.Line line) {
        Money list = line.listUnitPrice();
        boolean offered = list != null && list.amount().signum() > 0 && line.gross().amount().signum() == 0;
        Money discount = list == null ? null : list.times(line.quantity()).minus(line.gross());
        if (!offered && (discount == null || discount.amount().signum() <= 0)) {
            lines.add(item(line, line.unitPrice(), line.gross()));
            return;
        }
        lines.add(item(line, list, list.times(line.quantity())));
        if (offered) {
            lines.add("  " + OFFER);
        } else {
            String label = line.discountLabel() == null ? "" : " (" + line.discountLabel() + ")";
            lines.add("  Desconto" + label + ": " + MINUS + discount.amount().toPlainString());
        }
        lines.add("  Valor da linha: " + line.gross().amount().toPlainString());
    }

    private static String item(DocumentView.Line line, Money unitPrice, Money gross) {
        return plain(line.quantity()) + " x " + line.description() + " " + unitPrice.amount().toPlainString() + " = "
                + gross.amount().toPlainString();
    }

    /** A document issued before D97 has no snapshot: its on-account mark alone decides, as it did. */
    private static boolean wholeOnAccount(IssuedDocument document) {
        PaymentSnapshot payments = document.payments();
        return payments == null ? document.onAccount() : payments.status() == PaymentStatus.ON_ACCOUNT;
    }

    /** D97 rule 6: methods in snapshot order, credit always last, then what is still outstanding. */
    private static void addPayments(List<String> lines, PaymentSnapshot payments) {
        List<String> parts = new ArrayList<>();
        payments.methods().forEach(m -> parts.add(m.label() + ": " + m.amount().amount().toPlainString()));
        if (payments.credit().amount().signum() > 0) {
            parts.add(CREDIT_LABEL + ": " + payments.credit().amount().toPlainString());
        }
        String heading = switch (payments.status()) {
            case PARTIALLY_PAID -> "Pagamento parcial";
            case MIXED -> "Pagamento misto";
            default -> parts.size() > 1 ? "Pagamento misto" : "Pagamento";
        };
        if (!parts.isEmpty()) {
            lines.add(heading + " — " + String.join("; ", parts));
        }
        if (payments.outstanding().amount().signum() > 0) {
            lines.add("Saldo em aberto: " + payments.outstanding().amount().toPlainString());
        }
    }

    private static void addIfPresent(List<String> lines, String label, String value) {
        if (value != null) {
            lines.add(label + value);
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
