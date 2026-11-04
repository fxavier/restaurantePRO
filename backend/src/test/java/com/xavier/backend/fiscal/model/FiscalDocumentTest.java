package com.xavier.backend.fiscal.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.fiscal.IssueResult;
import com.xavier.backend.fiscal.exception.IllegalDocumentStateException;
import com.xavier.backend.fiscal.exception.InvalidSaleDraftException;
import com.xavier.backend.fiscal.model.dto.Buyer;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftLine;
import com.xavier.backend.fiscal.model.dto.SaleDraftCommand;
import com.xavier.backend.organization.model.dto.CompanyProfileView;
import com.xavier.backend.organization.model.dto.StoreSummary;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalDocumentTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
    static final UUID STORE = UUID.randomUUID();
    static final CompanyProfileView COMPANY = new CompanyProfileView("Restaurante Sol", "400123456", "MZ", MZN, 0);
    static final StoreSummary STORE_SUMMARY = new StoreSummary(STORE, "BX", "Baixa", ZoneId.of("Africa/Maputo"),
            LocalTime.of(5, 0), MZN, true, 0);

    // 2 x 58.00 at 16 %: gross 116.00, net 100.00, tax 16.00; 1 x 50.00 at 0 %: gross = net 50.00
    static final DraftLine BEER = new DraftLine(UUID.randomUUID(), "Cerveja 2M", new BigDecimal("2"), mzn("58.00"),
            new BigDecimal("16"), mzn("116.00"), mzn("100.00"), mzn("16.00"));
    static final DraftLine BREAD = new DraftLine(UUID.randomUUID(), "Pão", BigDecimal.ONE, mzn("50.00"),
            BigDecimal.ZERO, mzn("50.00"), mzn("50.00"), mzn("0.00"));
    static final DraftLine WINE = new DraftLine(UUID.randomUUID(), "Vinho", BigDecimal.ONE, mzn("232.00"),
            new BigDecimal("16.00"), mzn("232.00"), mzn("200.00"), mzn("32.00"));

    @Test
    void aSaleDraftSnapshotsCompanyStoreBuyerAndLinesAndSumsTheTaxPerRate() {
        Buyer buyer = new Buyer("Cliente Lda", "500999888", "Av. 24 de Julho, Maputo");

        DocumentView view = draft(List.of(BEER, BREAD, WINE), mzn("398.00"), mzn("350.00"), mzn("48.00"), buyer)
                .toView();

        assertThat(view.status()).isEqualTo("DRAFT");
        assertThat(view.number()).isNull();
        assertThat(view.companyName()).isEqualTo("Restaurante Sol");
        assertThat(view.companyTaxId()).isEqualTo("400123456");
        assertThat(view.storeName()).isEqualTo("Baixa");
        assertThat(view.buyer()).isEqualTo(buyer);
        assertThat(view.lines()).extracting(DocumentView.Line::description)
                .containsExactly("Cerveja 2M", "Pão", "Vinho");
        assertThat(view.taxes()).extracting(t -> t.rate().stripTrailingZeros().toPlainString(),
                        t -> t.net().amount().toPlainString(), t -> t.tax().amount().toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("0", "50.00", "0.00"),
                        org.assertj.core.groups.Tuple.tuple("16", "300.00", "48.00"));
    }

    @Test
    void aDraftNeedsAtLeastOneLine() {
        assertThatThrownBy(() -> draft(List.of(), mzn("0.00"), mzn("0.00"), mzn("0.00"), null))
                .isInstanceOf(InvalidSaleDraftException.class);
    }

    @Test
    void aLineWhoseGrossIsNotNetPlusTaxIsRejected() {
        DraftLine wrong = new DraftLine(UUID.randomUUID(), "Cerveja", BigDecimal.ONE, mzn("58.00"),
                new BigDecimal("16"), mzn("58.00"), mzn("50.00"), mzn("7.00"));

        assertThatThrownBy(() -> draft(List.of(wrong), mzn("58.00"), mzn("50.00"), mzn("7.00"), null))
                .isInstanceOf(InvalidSaleDraftException.class);
    }

    @Test
    void aLineWhoseGrossIsNotUnitPriceTimesQuantityIsRejected() {
        DraftLine wrong = new DraftLine(UUID.randomUUID(), "Cerveja", new BigDecimal("3"), mzn("58.00"),
                new BigDecimal("16"), mzn("116.00"), mzn("100.00"), mzn("16.00"));

        assertThatThrownBy(() -> draft(List.of(wrong), mzn("116.00"), mzn("100.00"), mzn("16.00"), null))
                .isInstanceOf(InvalidSaleDraftException.class);
    }

    @Test
    void totalsThatAreNotTheSumOfTheLinesAreRejected() {
        assertThatThrownBy(() -> draft(List.of(BEER), mzn("117.00"), mzn("101.00"), mzn("16.00"), null))
                .isInstanceOf(InvalidSaleDraftException.class);
    }

    @Test
    void aDraftInAnotherCurrencyThanTheCompanyIsRejected() {
        Currency eur = Currency.getInstance("EUR");
        DraftLine euroLine = new DraftLine(UUID.randomUUID(), "Café", BigDecimal.ONE, Money.of("1.00", eur),
                BigDecimal.ZERO, Money.of("1.00", eur), Money.of("1.00", eur), Money.of("0.00", eur));

        assertThatThrownBy(() -> draft(List.of(euroLine), Money.of("1.00", eur), Money.of("1.00", eur),
                Money.of("0.00", eur), null))
                .isInstanceOf(InvalidSaleDraftException.class);
    }

    @Test
    void issuingADraftGivesItItsNumberOnceAndOnlyOnce() {
        FiscalDocument document = draft(List.of(BEER), mzn("116.00"), mzn("100.00"), mzn("16.00"), null);
        FiscalSeries series = FiscalSeries.create(STORE, DocumentType.SALE, "A", 1, NOW);
        UUID employee = UUID.randomUUID();
        document.recordIssueFailure("printer on fire", NOW);

        document.issue(series, series.takeNextNumber(NOW), NOW, employee, UUID.randomUUID(), "internal", true,
                new IssueResult("hash", null));

        DocumentView view = document.toView();
        assertThat(view.status()).isEqualTo("ISSUED");
        assertThat(view.seriesCode()).isEqualTo("A");
        assertThat(view.number()).isEqualTo(1L);
        assertThat(view.issuedAt()).isEqualTo(NOW);
        assertThat(view.issuedByEmployeeId()).isEqualTo(employee);
        assertThat(view.nonFiscal()).isTrue();
        assertThat(view.lastError()).isNull();
        assertThatThrownBy(() -> document.issue(series, 2, NOW, employee, null, "internal", true,
                new IssueResult("hash", null))).isInstanceOf(IllegalDocumentStateException.class);
        assertThatThrownBy(() -> document.recordIssueFailure("late", NOW))
                .isInstanceOf(IllegalDocumentStateException.class);
    }

    @Test
    void aFailedIssueLeavesAVisibleDraft() {
        FiscalDocument document = draft(List.of(BEER), mzn("116.00"), mzn("100.00"), mzn("16.00"), null);

        document.recordIssueFailure("adapter down", NOW);

        assertThat(document.toView().status()).isEqualTo("DRAFT");
        assertThat(document.toView().lastError()).isEqualTo("adapter down");
        assertThat(document.toView().lastErrorAt()).isEqualTo(NOW);
    }

    static FiscalDocument draft(List<DraftLine> lines, Money gross, Money net, Money tax, Buyer buyer) {
        return FiscalDocument.saleDraft(new SaleDraftCommand(STORE, UUID.randomUUID(), buyer, lines, gross, net, tax),
                COMPANY, STORE_SUMMARY, NOW);
    }

    static Money mzn(String amount) {
        return Money.of(amount, MZN);
    }
}
