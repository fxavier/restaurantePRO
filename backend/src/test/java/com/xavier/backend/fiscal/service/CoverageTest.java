package com.xavier.backend.fiscal.service;

import static com.xavier.backend.fiscal.FiscalFixtures.asEmployee;
import static com.xavier.backend.fiscal.FiscalFixtures.mzn;
import static com.xavier.backend.fiscal.FiscalFixtures.saleDraft;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.fiscal.FiscalModuleTest;
import com.xavier.backend.fiscal.model.dto.Coverage;
import com.xavier.backend.fiscal.model.dto.Coverage.CoveredLine;
import com.xavier.backend.fiscal.model.dto.DocumentView;
import com.xavier.backend.fiscal.model.dto.DraftCommand;
import com.xavier.backend.fiscal.model.dto.DraftLine;
import com.xavier.backend.fiscal.model.dto.SourceType;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FiscalApi.coverage and documentsOfOrder (D70 step 1): per source line, the quantity, gross and tax already on the
 * order's ISSUED documents and on its DRAFT ones, whatever their source (partition, residual, or an R2 document).
 */
@FiscalModuleTest
class CoverageTest {

    static final BigDecimal RATE = new BigDecimal("16");

    @Autowired
    FiscalApi fiscal;

    @Autowired
    FiscalFixtures fixtures;

    @Autowired
    TransactionTemplate tx;

    // @ApplicationModuleTest runs one instance per class: every test gets fresh ids here
    UUID store;
    UUID order;
    UUID beer;
    UUID steak;

    @BeforeEach
    void storeWithSeries() throws Exception {
        order = UUID.randomUUID();
        beer = UUID.randomUUID();
        steak = UUID.randomUUID();
        store = fixtures.newStore();
        fixtures.newSeries(store, "A", 1);
    }

    @Test
    void anOrderWithoutDocumentsHasNoCoverage() {
        Coverage coverage = fiscal.coverage(order);

        assertThat(coverage.issued()).isEmpty();
        assertThat(coverage.draft()).isEmpty();
        assertThat(coverage.draftDocumentIds()).isEmpty();
        assertThat(fiscal.documentsOfOrder(order)).isEmpty();
    }

    @Test
    void issuedAndDraftDocumentsAreSummedPerSourceLineSeparately() {
        UUID first = issue(draft(SourceType.PARTITION, UUID.randomUUID(), line(beer, "1", "58.00"),
                line(steak, "0.5", "450.00")));
        UUID second = issue(draft(SourceType.PARTITION, UUID.randomUUID(), line(beer, "2", "58.00")));
        UUID pending = draft(SourceType.ORDER, order, line(steak, "0.5", "450.00"), line(beer, "1", "58.00"));

        Coverage coverage = fiscal.coverage(order);

        assertThat(coverage.issued()).containsExactlyInAnyOrder(
                covered(beer, "3.000", "174.00", "24.00"),
                covered(steak, "0.500", "225.00", "31.03"));
        assertThat(coverage.draft()).containsExactlyInAnyOrder(
                covered(steak, "0.500", "225.00", "31.03"),
                covered(beer, "1.000", "58.00", "8.00"));
        assertThat(coverage.draftDocumentIds()).containsExactly(pending);
        assertThat(fiscal.documentsOfOrder(order)).extracting(DocumentView::id)
                .containsExactly(first, second, pending);
    }

    @Test
    void anR2DocumentOfTheOrderCountsAndOtherOrdersDoNot() {
        UUID r2 = tx.execute(s -> fiscal.createSaleDraft(saleDraft(store, order, null))).id();
        UUID otherOrder = UUID.randomUUID();
        tx.execute(s -> fiscal.createDraft(new DraftCommand(store, SourceType.PARTITION, UUID.randomUUID(),
                otherOrder, null, null, null, false, List.of(line(beer, "1", "58.00")), mzn("58.00"),
                mzn("50.00"), mzn("8.00"))));

        Coverage coverage = fiscal.coverage(order);

        assertThat(coverage.issued()).isEmpty();
        assertThat(coverage.draft()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualByComparingTo("2");
            assertThat(line.gross()).isEqualTo(mzn("116.00"));
        });
        assertThat(coverage.draftDocumentIds()).containsExactly(r2);
        assertThat(fiscal.documentsOfOrder(order)).extracting(DocumentView::id).containsExactly(r2);
    }

    private UUID draft(SourceType type, UUID source, DraftLine... lines) {
        Money gross = mzn("0");
        Money net = mzn("0");
        Money tax = mzn("0");
        for (DraftLine line : lines) {
            gross = gross.plus(line.gross());
            net = net.plus(line.net());
            tax = tax.plus(line.tax());
        }
        DraftCommand command = new DraftCommand(store, type, source, order, LocalDate.of(2026, 11, 4), null, null,
                false, List.of(lines), gross, net, tax);
        return tx.execute(s -> fiscal.createDraft(command)).id();
    }

    private UUID issue(UUID draft) {
        fixtures.recordPaid(draft);
        UUID employee = UUID.randomUUID();
        return asEmployee(store, employee, UUID.randomUUID(), () -> fiscal.issue(draft, employee)).id();
    }

    /** D59: gross = unit × quantity, net = gross / 1.16, tax = gross − net. */
    static DraftLine line(UUID sourceLine, String quantity, String unitPrice) {
        Money unit = mzn(unitPrice);
        Money gross = unit.times(new BigDecimal(quantity));
        Money net = gross.divide(new BigDecimal("1.16"));
        return new DraftLine(sourceLine, "Item", new BigDecimal(quantity), unit, RATE, gross, net, gross.minus(net));
    }

    static CoveredLine covered(UUID sourceLine, String quantity, String gross, String tax) {
        return new CoveredLine(sourceLine, new BigDecimal(quantity), mzn(gross), mzn(tax));
    }
}
