package com.xavier.backend.settlement.reconciliation.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.settlement.reconciliation.model.OrderFacts.Charge;
import com.xavier.backend.settlement.reconciliation.model.OrderFacts.Document;
import com.xavier.backend.settlement.reconciliation.model.OrderFacts.Line;
import com.xavier.backend.settlement.reconciliation.model.dto.DocumentException;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * D80, amended by D96: the exceptions computed from an order's facts, each a bug alarm or a stuck pendency. A tax
 * difference is explained only by a recorded pending correction: open, it is listed; resolved, it is explained.
 */
class DocumentsControlRulesTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    static final BigDecimal VAT = new BigDecimal("16");
    static final UUID ORDER = UUID.fromString("0190a000-0000-7000-8000-0000000000a1");
    static final UUID LINE = UUID.fromString("0190a000-0000-7000-8000-0000000000b1");
    static final UUID DOC = UUID.fromString("0190a000-0000-7000-8000-0000000000d1");
    static final UUID CHARGE = UUID.fromString("0190a000-0000-7000-8000-0000000000e1");

    static Money m(String amount) {
        return Money.of(amount, MZN);
    }

    /** A closed order of 2 x 12.50 (tax 3.45), invoiced by one ISSUED document and paid in full. */
    static Builder settled() {
        return new Builder();
    }

    static final class Builder {
        String lifecycle = "CLOSED";
        List<Line> lines = new ArrayList<>(List.of(new Line(LINE, new BigDecimal("2"), VAT, m("3.45"))));
        List<Document> documents = new ArrayList<>(List.of(issued(DOC, "25.00", "3.45", false)));
        Map<UUID, BigDecimal> covered = new java.util.HashMap<>(Map.of(LINE, new BigDecimal("2")));
        Money paid = m("25.00");
        List<Charge> charges = new ArrayList<>();
        List<OrderFacts.Correction> corrections = new ArrayList<>();
        Map<BigDecimal, Money> unassigned = Map.of();

        OrderFacts facts() {
            return new OrderFacts(ORDER, lifecycle, MZN, lines, documents, covered, paid, charges, corrections,
                    unassigned);
        }

        List<String> kinds() {
            return DocumentsControl.check(facts(), NOW).exceptions().stream().map(DocumentException::kind).toList();
        }
    }

    static Document issued(UUID id, String gross, String tax, boolean onAccount) {
        return new Document(id, "ISSUED", "ORDER", ORDER, onAccount, m(gross), Map.of(VAT, m(tax)), null);
    }

    @Test
    void aSettledClosedOrderHasNoException() {
        DocumentsControl.Findings findings = DocumentsControl.check(settled().facts(), NOW);

        assertThat(findings.exceptions()).isEmpty();
        assertThat(findings.roundingDifferences()).isEmpty();
    }

    @Test
    void anIssuedDocumentNotPaidNorOnAccountAfterTheCloseIsUnpaid() {
        Builder order = settled();
        order.paid = m("20.00");

        assertThat(order.kinds()).containsExactly("DOCUMENT_UNPAID");
    }

    @Test
    void aPostedChargeCoversItsOnAccountDocument() {
        Builder order = settled();
        order.paid = m("0.00");
        order.documents = List.of(issued(DOC, "25.00", "3.45", true));
        order.charges = List.of(new Charge(CHARGE, "POSTED", DOC, m("25.00"), NOW.minus(Duration.ofDays(1)),
                true));

        assertThat(order.kinds()).isEmpty();
    }

    @Test
    void anOnAccountDocumentWithoutAPostedChargeIsAnException() {
        Builder order = settled();
        order.documents = List.of(issued(DOC, "25.00", "3.45", true));

        assertThat(order.kinds()).containsExactly("ON_ACCOUNT_WITHOUT_CHARGE");
    }

    @Test
    void aPostedChargeWithoutItsLedgerDebitIsAnException() {
        Builder order = settled();
        order.paid = m("0.00");
        order.documents = List.of(issued(DOC, "25.00", "3.45", true));
        order.charges = List.of(new Charge(CHARGE, "POSTED", DOC, m("25.00"), NOW, false));

        assertThat(order.kinds()).containsExactly("CHARGE_DEBIT_MISSING");
    }

    @Test
    void aReservedChargeIsStaleAfterAnHour() {
        Builder order = settled();
        order.lifecycle = "OPEN";
        order.charges = List.of(new Charge(CHARGE, "RESERVED", DOC, m("25.00"), NOW.minus(Duration.ofMinutes(61)),
                false));
        assertThat(order.kinds()).containsExactly("CHARGE_RESERVED_STALE");

        order.charges = List.of(new Charge(CHARGE, "RESERVED", DOC, m("25.00"), NOW.minus(Duration.ofMinutes(59)),
                false));
        assertThat(order.kinds()).isEmpty();
    }

    @Test
    void aDraftIsStaleWhenItsIssueFailedAnHourAgoOrItsOrderClosed() {
        Builder order = settled();
        order.lifecycle = "OPEN";
        order.documents = List.of(new Document(DOC, "DRAFT", "ORDER", ORDER, false, m("25.00"), Map.of(),
                NOW.minus(Duration.ofMinutes(61))));
        assertThat(order.kinds()).containsExactly("DRAFT_STALE");

        order.documents = List.of(new Document(DOC, "DRAFT", "ORDER", ORDER, false, m("25.00"), Map.of(),
                NOW.minus(Duration.ofMinutes(30))));
        assertThat(order.kinds()).isEmpty();

        order.lifecycle = "CLOSED";
        order.documents = new ArrayList<>(List.of(issued(UUID.randomUUID(), "25.00", "3.45", false),
                new Document(DOC, "DRAFT", "PARTITION", UUID.randomUUID(), false, m("0.00"), Map.of(), null)));
        assertThat(order.kinds()).containsExactly("DRAFT_STALE");
    }

    @Test
    void aClosedOrderWhoseIssuedQuantityIsNotItsNetQuantityIsAnException() {
        Builder order = settled();
        order.covered = Map.of(LINE, new BigDecimal("1"));

        assertThat(DocumentsControl.check(order.facts(), NOW).exceptions()).singleElement()
                .satisfies(e -> {
                    assertThat(e.kind()).isEqualTo("COVERAGE_MISMATCH");
                    assertThat(e.lineId()).isEqualTo(LINE);
                });
    }

    @Test
    void aCoveredLineTheOrderNoLongerBillsIsACoverageMismatch() {
        Builder order = settled();
        UUID gone = UUID.randomUUID();
        order.covered = Map.of(LINE, new BigDecimal("2"), gone, BigDecimal.ONE);

        assertThat(DocumentsControl.check(order.facts(), NOW).exceptions()).extracting(DocumentException::lineId)
                .containsExactly(gone);
    }

    static final UUID ADJUSTMENT = UUID.fromString("0190a000-0000-7000-8000-0000000000f1");

    /** D96: the order recomputes 1.72, its ISSUED document carries 1.73 and no residual could take the cent. */
    static Builder aCentNoDocumentCouldTake(String correctionReference) {
        Builder order = settled();
        order.lines = List.of(new Line(LINE, BigDecimal.ONE, VAT, m("1.72")));
        order.covered = Map.of(LINE, BigDecimal.ONE);
        order.documents = List.of(issued(DOC, "12.50", "1.73", false));
        order.paid = m("12.50");
        order.corrections.add(new OrderFacts.Correction(ADJUSTMENT, VAT, LINE, DOC, m("-0.01"),
                correctionReference));
        return order;
    }

    @Test
    void anOpenPendingCorrectionIsListedWithItsDocumentAndLine() {
        DocumentsControl.Findings findings = DocumentsControl.check(aCentNoDocumentCouldTake(null).facts(), NOW);

        assertThat(findings.roundingDifferences()).isEmpty();
        assertThat(findings.exceptions()).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("TAX_CORRECTION_PENDING");
            assertThat(e.documentId()).isEqualTo(DOC);
            assertThat(e.lineId()).isEqualTo(LINE);
            assertThat(e.detail()).contains("-0.01");
        });
    }

    @Test
    void aResolvedCorrectionIsAnExplainedRoundingDifferenceWithItsReference() {
        DocumentsControl.Findings findings = DocumentsControl.check(aCentNoDocumentCouldTake("NC 7/2026").facts(),
                NOW);

        assertThat(findings.exceptions()).isEmpty();
        assertThat(findings.roundingDifferences()).singleElement().satisfies(r -> {
            assertThat(r.orderId()).isEqualTo(ORDER);
            assertThat(r.ratePercent()).isEqualByComparingTo("16");
            assertThat(r.difference()).isEqualTo(m("-0.01"));
            assertThat(r.adjustmentId()).isEqualTo(ADJUSTMENT);
            assertThat(r.correctionReference()).isEqualTo("NC 7/2026");
        });
    }

    @Test
    void theSplitsDriftAloneNoLongerExplainsADifference() {
        Builder order = aCentNoDocumentCouldTake(null);
        order.corrections.clear(); // as built before D96: a drift, but no recorded adjustment

        assertThat(order.kinds()).containsExactly("TAX_MISMATCH");
    }

    @Test
    void aDriftNobodyPlacedOnAClosedOrderIsABugAlarm() {
        Builder order = aCentNoDocumentCouldTake(null);
        order.corrections.clear();
        order.unassigned = Map.of(VAT, m("-0.01"));

        assertThat(order.kinds()).containsExactly("TAX_DRIFT_UNASSIGNED");
    }

    @Test
    void aCentOfTaxTheSplitDoesNotExplainIsATaxMismatch() {
        Builder order = settled(); // no recorded correction: the expected difference is zero
        order.documents = List.of(issued(DOC, "25.00", "3.44", false));

        assertThat(order.kinds()).containsExactly("TAX_MISMATCH");
    }

    @Test
    void manyLinesDoNotWidenTheTolerance() {
        Builder order = settled();
        List<Line> twenty = new ArrayList<>();
        Map<UUID, BigDecimal> covered = new java.util.HashMap<>();
        for (int i = 0; i < 20; i++) {
            UUID line = UUID.randomUUID();
            twenty.add(new Line(line, BigDecimal.ONE, VAT, m("1.72")));
            covered.put(line, BigDecimal.ONE);
        }
        order.lines = twenty; // the lines' tax 34.40; the documents carry 0.15 less
        order.covered = covered;
        order.documents = List.of(issued(DOC, "250.00", "34.25", false));
        order.paid = m("250.00");

        assertThat(order.kinds()).containsExactly("TAX_MISMATCH");
    }

    @Test
    void moreThanACentOfTaxPerLineIsATaxMismatch() {
        Builder order = settled();
        order.documents = List.of(issued(DOC, "25.00", "3.47", false));

        assertThat(order.kinds()).containsExactly("TAX_MISMATCH");
    }
}
