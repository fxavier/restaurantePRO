package com.xavier.backend.fiscal.adapter.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.fiscal.IssueRequest;
import com.xavier.backend.fiscal.IssuedDocument;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.event.DocumentPrintout;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class InternalFiscalAdapterTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final Instant AT = Instant.parse("2026-11-04T10:30:00Z");

    final InternalFiscalAdapter adapter = new InternalFiscalAdapter();

    @Test
    void itIsTheNonCertifiedInternalAdapter() {
        assertThat(adapter.code()).isEqualTo("internal");
        assertThat(adapter.certified()).isFalse();
    }

    @Test
    void eachDocumentIsChainedToThePreviousOneOfItsSeries() {
        String first = adapter.issue(request(1, "116.00", null)).adapterData();
        String second = adapter.issue(request(2, "116.00", first)).adapterData();

        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(adapter.issue(request(1, "116.00", null)).adapterData()).isEqualTo(first);
        assertThat(second).isNotEqualTo(first);
        assertThat(adapter.issue(request(2, "116.00", "tampered")).adapterData()).isNotEqualTo(second);
        assertThat(adapter.issue(request(2, "117.00", first)).adapterData()).isNotEqualTo(second);
        assertThat(adapter.issue(request(1, "116.00", null)).qrPayload()).isNull();
    }

    @Test
    void thePrintoutShowsTheSnapshotsTheLinesTheTaxSummaryAndTheTotal() {
        DocumentPrintout printout = adapter.printout(document(), false);

        assertThat(printout.lines()).contains("Restaurante Sol", "Baixa", "Contribuinte: 400123456",
                "Documento de venda A/12", "Data: 2026-11-04 12:30", "Cliente: Cliente Lda",
                "2 x Cerveja 2M 58.00 = 116.00", "Taxa 16%: base 100.00 imposto 16.00", "TOTAL 116.00 MZN");
        assertThat(printout.lines()).doesNotContain("CÓPIA");
        assertThat(printout.qrPayload()).isNull();
    }

    @Test
    void aCopySaysSo() {
        assertThat(adapter.printout(document(), true).lines()).contains("CÓPIA");
    }

    static IssueRequest request(long number, String total, String previous) {
        return new IssueRequest(UUID.fromString("0190a1b2-0000-7000-8000-000000000001"), "SALE", "A", number, AT,
                Money.of(total, MZN), Money.of("16.00", MZN), previous, false, null);
    }

    public static IssuedDocument document() {
        Money gross = Money.of("116.00", MZN);
        return new IssuedDocument(UUID.randomUUID(), "SALE", "A", 12, AT, ZoneId.of("Africa/Maputo"),
                "Restaurante Sol", "400123456", "MZ", "BX", "Baixa", new Buyer("Cliente Lda", null, null),
                List.of(new DocumentView.Line(1, null, "Cerveja 2M", new BigDecimal("2.000"),
                        Money.of("58.00", MZN), new BigDecimal("16.0000"), gross, Money.of("100.00", MZN),
                        Money.of("16.00", MZN))),
                List.of(new DocumentView.TaxSummary(new BigDecimal("16.0000"), Money.of("100.00", MZN),
                        Money.of("16.00", MZN))),
                gross, Money.of("100.00", MZN), Money.of("16.00", MZN), "hash", null, false, null);
    }
}
