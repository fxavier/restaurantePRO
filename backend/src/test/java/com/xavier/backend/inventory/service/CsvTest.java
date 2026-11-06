package com.xavier.backend.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** RFC 4180 fields, with spreadsheet formulas defused (a leading = + - @ tab or carriage return). */
class CsvTest {

    @Test
    void aFormulaLookingTextIsDefused() {
        assertThat(Csv.field("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(Csv.field("@cmd")).isEqualTo("'@cmd");
        assertThat(Csv.field("\t=1+1")).isEqualTo("'\t=1+1");
        assertThat(Csv.field("\r=1+1")).isEqualTo("\"'\r=1+1\"");
    }

    @Test
    void numbersAndPlainTextStayAsTheyAre() {
        assertThat(Csv.field("-12.3450")).isEqualTo("-12.3450");
        assertThat(Csv.field("Flour")).isEqualTo("Flour");
        assertThat(Csv.field(null)).isEmpty();
    }

    @Test
    void separatorsAndQuotesAreQuoted() {
        assertThat(Csv.field("a,b")).isEqualTo("\"a,b\"");
        assertThat(Csv.field("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
    }
}
