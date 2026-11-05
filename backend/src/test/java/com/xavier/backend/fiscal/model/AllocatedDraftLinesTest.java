package com.xavier.backend.fiscal.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.fiscal.exception.DraftMismatchException;
import com.xavier.backend.fiscal.exception.InvalidSaleDraftException;
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
 * D68 amendment (S28): the lines of a partition or residual draft carry settlement's allocated amounts. They are
 * checked with a bound instead of D59's exact recomputation: gross within 1 minor unit of round(unitPrice × q), net
 * within 2 of round(gross / (1 + rate/100)), net + tax = gross exactly. Unallocated drafts keep D59 exactly.
 */
class AllocatedDraftLinesTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final CompanyProfileView COMPANY = new CompanyProfileView("C", "1", "MZ", MZN, 0);
    static final StoreSummary STORE = new StoreSummary(UUID.randomUUID(), "S", "S", ZoneId.of("Africa/Maputo"),
            LocalTime.of(5, 0), MZN, true, 0);

    // the third share of 3 x 1.00 at 16 % split 1 + 1 + 1: gross 1.00, net 0.87, tax 0.13 (exact would be 0.86)
    static final DraftLine THIRD_WHOLE_SHARE = line("1", "1.00", "1.00", "0.87", "0.13");
    // a share of 0.500 x 0.05 split 0.250 + 0.250: gross 0.02, though round(0.05 x 0.250) = 0.01
    static final DraftLine FRACTIONAL_SHARE = line("0.250", "0.05", "0.02", "0.02", "0.00");

    @Test
    void anAllocatedDraftAcceptsTheThirdWholeShareOfATaxSplit() {
        assertThatCode(() -> draft(true, THIRD_WHOLE_SHARE)).doesNotThrowAnyException();
    }

    @Test
    void anUnallocatedDraftStillRejectsTheThirdWholeShare() {
        assertThatThrownBy(() -> draft(false, THIRD_WHOLE_SHARE)).isInstanceOf(InvalidSaleDraftException.class)
                .hasMessageContaining("net must be gross / (1 + rate/100)");
    }

    @Test
    void anAllocatedDraftAcceptsAFractionalShareOneMinorUnitOff() {
        assertThatCode(() -> draft(true, FRACTIONAL_SHARE)).doesNotThrowAnyException();
    }

    @Test
    void anUnallocatedDraftStillRejectsTheFractionalShare() {
        assertThatThrownBy(() -> draft(false, FRACTIONAL_SHARE)).isInstanceOf(InvalidSaleDraftException.class)
                .hasMessageContaining("gross must be unit price × quantity");
    }

    @Test
    void anAllocatedLineWhoseGrossIsTwoMinorUnitsOffIsRejected() {
        assertThatThrownBy(() -> draft(true, line("1", "1.00", "1.02", "0.88", "0.14")))
                .isInstanceOf(InvalidSaleDraftException.class).hasMessageContaining("gross");
    }

    @Test
    void anAllocatedLineWhoseNetIsThreeMinorUnitsOffIsRejected() {
        assertThatThrownBy(() -> draft(true, line("1", "1.00", "1.00", "0.89", "0.11")))
                .isInstanceOf(InvalidSaleDraftException.class).hasMessageContaining("net");
    }

    @Test
    void anAllocatedLineWithANegativeTaxIsRejected() {
        assertThatThrownBy(() -> draft(true, line("1", "0.01", "0.01", "0.02", "-0.01")))
                .isInstanceOf(InvalidSaleDraftException.class);
    }

    @Test
    void anAllocatedLineStillNeedsNetPlusTaxToBeGross() {
        assertThatThrownBy(() -> draft(true, line("1", "1.00", "1.00", "0.86", "0.13")))
                .isInstanceOf(InvalidSaleDraftException.class).hasMessageContaining("net + tax must equal gross");
    }

    @Test
    void theAllocatedFlagIsPartOfTheDraftContent() {
        DraftLine exact = line("1", "1.00", "1.00", "0.86", "0.14");
        FiscalDocument document = draft(false, exact);
        ReflectionTestUtils.setField(document, "id", UUID.randomUUID());

        assertThatThrownBy(() -> document.amendDraft(command(true, exact), Instant.now()))
                .isInstanceOf(DraftMismatchException.class);
        assertThatCode(() -> document.amendDraft(command(false, exact), Instant.now())).doesNotThrowAnyException();
        assertThat(document.toView().totalGross()).isEqualTo(Money.of("1.00", MZN));
    }

    static FiscalDocument draft(boolean allocated, DraftLine line) {
        return FiscalDocument.draft(command(allocated, line), COMPANY, STORE, Instant.now());
    }

    static DraftCommand command(boolean allocated, DraftLine line) {
        return new DraftCommand(STORE.id(), SourceType.PARTITION, UUID.fromString(
                "0190a000-0000-7000-8000-0000000000a1"), UUID.fromString("0190a000-0000-7000-8000-0000000000a2"),
                null, null, null, false, List.of(line), line.gross(), line.net(), line.tax(), allocated);
    }

    static DraftLine line(String quantity, String unitPrice, String gross, String net, String tax) {
        return new DraftLine(UUID.fromString("0190a000-0000-7000-8000-0000000000a3"), "Item",
                new BigDecimal(quantity), Money.of(unitPrice, MZN), new BigDecimal("16"), Money.of(gross, MZN),
                Money.of(net, MZN), Money.of(tax, MZN));
    }
}
