package com.xavier.backend.fiscal.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.fiscal.exception.DraftMismatchException;
import com.xavier.backend.fiscal.exception.InvalidSaleDraftException;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.DraftLine;
import com.xavier.backend.fiscal.model.dto.SourceType;
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
import org.springframework.test.util.ReflectionTestUtils;

/**
 * D101/D107 (S43): a discounted line carries its effective unit price, the list price before the discount and a
 * label. The D59 check (and S28's bound) still runs on the effective unit price only; the list price is never below
 * it and is part of the draft's content.
 */
class DiscountedDraftLineTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final CompanyProfileView COMPANY = new CompanyProfileView("C", "1", "MZ", MZN, 0);
    static final StoreSummary STORE = new StoreSummary(UUID.randomUUID(), "S", "S", ZoneId.of("Africa/Maputo"),
            LocalTime.of(5, 0), MZN, true, 0);

    // 10 % off 1.25 per unit is 1.13 (D101); 2 x 1.13 = 2.26 at 16 %: net 1.95, tax 0.31
    static final DraftLine HAPPY_HOUR = line("2", "1.13", "2.26", "1.95", "0.31", "1.25", "Happy hour");
    static final DraftLine OFFER = line("1", "0.00", "0.00", "0.00", "0.00", "1.25", null);

    @Test
    void aDiscountedLineIsCheckedOnItsEffectiveUnitPriceAndKeepsItsListPriceAndLabel() {
        DocumentView.Line line = draft(false, HAPPY_HOUR).toView().lines().getFirst();

        assertThat(line.unitPrice()).isEqualTo(mzn("1.13"));
        assertThat(line.gross()).isEqualTo(mzn("2.26"));
        assertThat(line.listUnitPrice()).isEqualTo(mzn("1.25"));
        assertThat(line.discountLabel()).isEqualTo("Happy hour");
    }

    @Test
    void aListPriceEqualToTheUnitPriceWithNoLabelIsNoDiscount() {
        // an allocated share 3 x 1.00 = 2.99 must not print a phantom 0.01 "discount"; an undiscounted redraft
        // must match an R3 draft (S43 review)
        DraftLine sameAsUnit = line("3", "1.00", "2.99", "2.58", "0.41", "1.00", null);

        assertThat(sameAsUnit.listUnitPrice()).isNull();
        assertThat(sameAsUnit.discountLabel()).isNull();
        assertThat(line("1", "1.00", "1.00", "0.86", "0.14", "1.00", "Promo").listUnitPrice()).isNotNull();
    }

    @Test
    void aDiscountedLineWhoseGrossFollowsTheListPriceIsRejected() {
        assertThatThrownBy(() -> draft(false, line("2", "1.13", "2.50", "2.16", "0.34", "1.25", "Happy hour")))
                .isInstanceOf(InvalidSaleDraftException.class)
                .hasMessageContaining("gross must be unit price × quantity");
    }

    @Test
    void anOfferedLineHasZeroAmounts() {
        DocumentView.Line line = draft(false, OFFER).toView().lines().getFirst();

        assertThat(line.gross()).isEqualTo(mzn("0.00"));
        assertThat(line.listUnitPrice()).isEqualTo(mzn("1.25"));
    }

    @Test
    void anAllocatedDiscountedLineIsBoundedOnItsEffectiveUnitPrice() {
        // a share of 0.90 (10 % off 1.00) given 1 minor unit up by the allocation: gross 0.91 = net 0.78 + tax 0.13
        assertThatCode(() -> draft(true, line("1", "0.90", "0.91", "0.78", "0.13", "1.00", "Gerente")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> draft(true, line("1", "0.90", "1.00", "0.86", "0.14", "1.00", "Gerente")))
                .isInstanceOf(InvalidSaleDraftException.class).hasMessageContaining("allocated gross");
    }

    @Test
    void aListUnitPriceBelowTheUnitPriceIsRejected() {
        assertThatThrownBy(() -> draft(false, line("2", "1.13", "2.26", "1.95", "0.31", "1.12", "Happy hour")))
                .isInstanceOf(InvalidSaleDraftException.class)
                .hasMessageContaining("list unit price cannot be below the unit price");
    }

    @Test
    void aListUnitPriceInAnotherCurrencyIsRejected() {
        DraftLine euros = withList(HAPPY_HOUR, Money.of("1.25", Currency.getInstance("EUR")), "Happy hour");

        assertThatThrownBy(() -> draft(false, euros)).isInstanceOf(InvalidSaleDraftException.class)
                .hasMessageContaining("every amount must be in MZN");
    }

    @Test
    void aDiscountLabelNeedsAListUnitPrice() {
        assertThatThrownBy(() -> draft(false, withList(HAPPY_HOUR, null, "Happy hour")))
                .isInstanceOf(InvalidSaleDraftException.class)
                .hasMessageContaining("a discount label needs a list unit price");
    }

    @Test
    void aDiscountLabelHasAtMostEightyCharacters() {
        assertThatCode(() -> draft(false, withList(HAPPY_HOUR, mzn("1.25"), "x".repeat(80))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> draft(false, withList(HAPPY_HOUR, mzn("1.25"), "x".repeat(81))))
                .isInstanceOf(InvalidSaleDraftException.class).hasMessageContaining("longer than 80");
    }

    @Test
    void aLineWithoutADiscountHasNoListPriceNorLabel() {
        DraftLine plain = new DraftLine(HAPPY_HOUR.sourceLineId(), "Café", new BigDecimal("2"), mzn("1.13"),
                new BigDecimal("16"), mzn("2.26"), mzn("1.95"), mzn("0.31"));

        DocumentView.Line line = draft(false, plain).toView().lines().getFirst();

        assertThat(line.listUnitPrice()).isNull();
        assertThat(line.discountLabel()).isNull();
    }

    @Test
    void theListPriceAndTheLabelArePartOfTheDraftContent() {
        FiscalDocument document = draft(false, HAPPY_HOUR);
        ReflectionTestUtils.setField(document, "id", UUID.randomUUID());

        assertThatThrownBy(() -> document.amendDraft(command(false, withList(HAPPY_HOUR, mzn("1.26"), "Happy hour")),
                Instant.now())).isInstanceOf(DraftMismatchException.class);
        assertThatThrownBy(() -> document.amendDraft(command(false, withList(HAPPY_HOUR, mzn("1.25"), "Outro")),
                Instant.now())).isInstanceOf(DraftMismatchException.class);
        assertThatThrownBy(() -> document.amendDraft(command(false, withList(HAPPY_HOUR, null, null)),
                Instant.now())).isInstanceOf(DraftMismatchException.class);
        assertThatCode(() -> document.amendDraft(command(false, HAPPY_HOUR), Instant.now()))
                .doesNotThrowAnyException();
    }

    @Test
    void theDocumentFactsCarryTheListPriceAndTheLabel() {
        DocumentView.Line line = draft(false, HAPPY_HOUR).toFacts(() -> STORE).lines().getFirst();

        assertThat(line.listUnitPrice()).isEqualTo(mzn("1.25"));
        assertThat(line.discountLabel()).isEqualTo("Happy hour");
    }

    static FiscalDocument draft(boolean allocated, DraftLine line) {
        return FiscalDocument.draft(command(allocated, line), COMPANY, STORE, Instant.now());
    }

    static DraftCommand command(boolean allocated, DraftLine line) {
        return new DraftCommand(STORE.id(), SourceType.PARTITION, UUID.fromString(
                "0190a000-0000-7000-8000-0000000000b1"), UUID.fromString("0190a000-0000-7000-8000-0000000000b2"),
                null, null, null, false, List.of(line), line.gross(), line.net(), line.tax(), allocated);
    }

    static DraftLine line(String quantity, String unitPrice, String gross, String net, String tax, String list,
                          String label) {
        return new DraftLine(UUID.fromString("0190a000-0000-7000-8000-0000000000b3"), "Café",
                new BigDecimal(quantity), mzn(unitPrice), new BigDecimal("16"), mzn(gross), mzn(net), mzn(tax),
                "IVA", list == null ? null : mzn(list), label);
    }

    static DraftLine withList(DraftLine line, Money list, String label) {
        return new DraftLine(line.sourceLineId(), line.description(), line.quantity(), line.unitPrice(),
                line.taxRatePercent(), line.gross(), line.net(), line.tax(), line.taxCode(), list, label);
    }

    static Money mzn(String amount) {
        return Money.of(amount, MZN);
    }
}
