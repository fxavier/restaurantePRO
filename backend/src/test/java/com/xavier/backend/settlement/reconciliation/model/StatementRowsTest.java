package com.xavier.backend.settlement.reconciliation.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.settlement.reconciliation.StatementLine;
import com.xavier.backend.settlement.reconciliation.model.dto.StatementError;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** D78, P37: the one canonical statement CSV, and the same checks on lines a provider returns. */
class StatementRowsTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final Instant FROM = Instant.parse("2026-11-04T22:00:00Z");
    static final Instant TO = Instant.parse("2026-11-05T22:00:00Z");
    static final String HEADER = "occurred_at,amount,fee,reference,authorization_code,line_ref\n";

    static StatementRows.Result csv(String body) {
        return StatementRows.fromCsv((HEADER + body).getBytes(StandardCharsets.UTF_8), MZN, FROM, TO);
    }

    @Test
    void readsEveryColumnOfALine() {
        StatementRows.Result result = csv("2026-11-05T10:15:00+02:00,25.00,0.50,REF-1,A123,L-9\n");

        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).containsExactly(new StatementRow(1, Instant.parse("2026-11-05T08:15:00Z"),
                Money.of("25.00", MZN), Money.of("0.50", MZN), "REF-1", "A123", "L-9"));
    }

    @Test
    void emptyOptionalColumnsAreAbsentAndAnEmptyFeeIsZero() {
        StatementRows.Result result = csv("2026-11-05T08:15:00Z,25.00,,, ,\n");

        assertThat(result.rows().getFirst()).isEqualTo(new StatementRow(1, Instant.parse("2026-11-05T08:15:00Z"),
                Money.of("25.00", MZN), Money.of("0", MZN), null, null, null));
    }

    @Test
    void aQuotedFieldMayHoldACommaAndBlankLinesAreSkipped() {
        StatementRows.Result result = csv("\n2026-11-05T08:15:00Z,25.00,0,\"REF,1\",,\n\n");

        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).singleElement().extracting(StatementRow::reference).isEqualTo("REF,1");
    }

    @Test
    void aHeaderOnlyStatementHasNoRows() {
        StatementRows.Result result = csv("");

        assertThat(result.errors()).isEmpty();
        assertThat(result.rows()).isEmpty();
    }

    @Test
    void anotherHeaderIsRefusedAsLineZero() {
        StatementRows.Result result = StatementRows.fromCsv("date,amount\n".getBytes(StandardCharsets.UTF_8), MZN,
                FROM, TO);

        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.line()).isZero();
            assertThat(error.field()).isEqualTo("header");
        });
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "yesterday,25.00,0,,,                    | occurred_at",
            "2026-11-04T21:59:59Z,25.00,0,,,         | occurred_at",
            "2026-11-05T22:00:00Z,25.00,0,,,         | occurred_at",
            "2026-11-05T08:15:00Z,abc,0,,,           | amount",
            "2026-11-05T08:15:00Z,0,0,,,             | amount",
            "2026-11-05T08:15:00Z,-5.00,0,,,         | amount",
            "2026-11-05T08:15:00Z,25.001,0,,,        | amount",
            "2026-11-05T08:15:00Z,25.00,-1,,,        | fee",
            "2026-11-05T08:15:00Z,25.00,0,,          | line"})
    void anInvalidValueIsReportedWithItsLineAndField(String line, String field) {
        StatementRows.Result result = csv("2026-11-05T08:15:00Z,10.00,0,,,\n" + line + "\n");

        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.line()).isEqualTo(2);
            assertThat(error.field()).isEqualTo(field);
        });
    }

    @Test
    void everyErrorOfTheFileIsReported() {
        StatementRows.Result result = csv("x,1,0,,,\n2026-11-05T08:15:00Z,y,0,,,\n");

        assertThat(result.errors()).extracting(StatementError::line, StatementError::field)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "occurred_at"),
                        org.assertj.core.groups.Tuple.tuple(2, "amount"));
    }

    @Test
    void providerLinesAreCheckedTheSameWay() {
        StatementRows.Result result = StatementRows.fromLines(List.of(
                new StatementLine(Instant.parse("2026-11-05T08:15:00Z"), new BigDecimal("25.00"), null, "R", null,
                        null),
                new StatementLine(Instant.parse("2026-11-05T08:16:00Z"), new BigDecimal("-1.00"), BigDecimal.ZERO,
                        null, null, null)), MZN, FROM, TO);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.errors()).extracting(StatementError::line, StatementError::field)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, "amount"));
    }

    @Test
    void providerLinesHaveTheCanonicalRenderingSoTheirDigestIsStable() {
        List<StatementLine> lines = List.of(new StatementLine(Instant.parse("2026-11-05T08:15:00Z"),
                new BigDecimal("25.0"), null, "R,1", null, "L1"));

        assertThat(new String(StatementRows.canonicalCsv(lines), StandardCharsets.UTF_8))
                .isEqualTo(HEADER + "2026-11-05T08:15:00Z,25.0,,\"R,1\",,L1\n");
        assertThat(StatementRows.fromCsv(StatementRows.canonicalCsv(lines), MZN, FROM, TO).rows())
                .isEqualTo(StatementRows.fromLines(lines, MZN, FROM, TO).rows());
    }
}
