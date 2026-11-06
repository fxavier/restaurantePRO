package com.xavier.backend.fiscal.adapter.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.fiscal.IssuedDocument;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * S43 (D101, D107): the internal printout shows a discounted line at its list price, then its discount
 * ({@code list gross − gross}) and the line's value; an offered line (0.00) says "Oferta". A line without a list price,
 * or at its list price, prints exactly as in R3, so the reprint of an older document does not change.
 */
class InternalAdapterDiscountPrintoutTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final String DISCOUNT = "  Desconto (Happy hour): −0.24";

    final InternalFiscalAdapter adapter = new InternalFiscalAdapter();

    @Test
    void theLinesPrintTheListPriceTheDiscountAndTheValue() {
        List<String> printed = adapter.printout(document(List.of(
                line(1, "Café", "2.000", "1.13", "2.26", "1.95", "0.31", "1.25", "Happy hour"),
                line(2, "Água", "1.000", "0.90", "0.90", "0.78", "0.12", "1.00", null),
                line(3, "Café", "1.000", "0.00", "0.00", "0.00", "0.00", "1.25", "Cortesia"),
                line(4, "Cerveja 2M", "1.000", "58.00", "58.00", "50.00", "8.00", null, null),
                line(5, "Pão", "1.000", "0.50", "0.50", "0.43", "0.07", "0.50", null))), false).lines();

        assertThat(between(printed)).containsExactly(
                "2 x Café 1.25 = 2.50",
                DISCOUNT,
                "  Valor da linha: 2.26",
                "1 x Água 1.00 = 1.00",
                "  Desconto: −0.10",
                "  Valor da linha: 0.90",
                "1 x Café 1.25 = 1.25",
                "  Oferta",
                "  Valor da linha: 0.00",
                "1 x Cerveja 2M 58.00 = 58.00",
                "1 x Pão 0.50 = 0.50");
    }

    @Test
    void aCopyPrintsTheSameLines() {
        IssuedDocument document = document(List.of(
                line(1, "Café", "2.000", "1.13", "2.26", "1.95", "0.31", "1.25", "Happy hour")));

        assertThat(between(adapter.printout(document, true).lines()))
                .isEqualTo(between(adapter.printout(document, false).lines()));
    }

    /** BUG-0001: U+2212 is in none of the printer tables, so every table prints the ASCII hyphen, never "?". */
    @ParameterizedTest
    @EnumSource(CodePage.class)
    void theDiscountMinusPrintsAsAHyphenOnEveryPrinterTable(CodePage table) {
        assertThat(new String(table.encode(DISCOUNT), table.charset()))
                .isEqualTo("  Desconto (Happy hour): -0.24");
    }

    @Test
    void aLabelOutsideTheTableKeepsTheRestOfTheLineReadable() {
        String printed = new String(CodePage.CP860.encode("  Desconto (“Sócio”): −1.00"),
                CodePage.CP860.charset());

        assertThat(printed).isEqualTo("  Desconto (\"Sócio\"): -1.00");
    }

    private static List<String> between(List<String> printout) {
        int first = printout.indexOf("--------------------------------");
        int last = printout.lastIndexOf("--------------------------------");
        return printout.subList(first + 1, last);
    }

    private static DocumentView.Line line(int no, String description, String quantity, String unit, String gross,
                                          String net, String tax, String list, String label) {
        return new DocumentView.Line(no, UUID.fromString("0190a000-0000-7000-8000-0000000000c" + no), description,
                new BigDecimal(quantity), mzn(unit), new BigDecimal("16.0000"), mzn(gross), mzn(net), mzn(tax),
                list == null ? null : mzn(list), label);
    }

    private static IssuedDocument document(List<DocumentView.Line> lines) {
        Money gross = lines.stream().map(DocumentView.Line::gross).reduce(mzn("0.00"), Money::plus);
        Money net = lines.stream().map(DocumentView.Line::net).reduce(mzn("0.00"), Money::plus);
        return new IssuedDocument(UUID.fromString("0190a000-0000-7000-8000-0000000000c0"), "SALE", "A", 7,
                Instant.parse("2026-11-04T17:59:00Z"), ZoneId.of("Africa/Maputo"), "Restaurante Sol", "400123456",
                "MZ", "BX", "Baixa", null, lines,
                List.of(new DocumentView.TaxSummary("IVA", new BigDecimal("16.0000"), net, gross.minus(net))),
                gross, net, gross.minus(net), "hash", null, false, null);
    }

    private static Money mzn(String amount) {
        return Money.of(amount, MZN);
    }
}
