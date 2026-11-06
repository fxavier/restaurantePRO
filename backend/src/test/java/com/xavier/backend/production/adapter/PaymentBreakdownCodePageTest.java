package com.xavier.backend.production.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.production.model.CodePage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * BUG-0001 (docs/qa/bugs/0001-payment-breakdown-dash-prints-as-question-mark.md): D97 rule 6 prints the payment
 * breakdown with an em dash (U+2014), which only WPC1252 has. A table that lacks a typographic character prints its
 * defined ASCII fallback, never {@code ?}; WPC1252 keeps the exact text.
 */
class PaymentBreakdownCodePageTest {

    private static final String BREAKDOWN = "Pagamento misto — Numerário: 20.00; A crédito: 30.00";
    private static final String TYPOGRAPHIC = "Mesa 1–4 “VIP” ‘a’ obrigado…";

    @ParameterizedTest
    @EnumSource(value = CodePage.class, names = {"CP437", "CP850", "CP860", "CP858"})
    void aTableWithoutTheEmDashPrintsAHyphen(CodePage table) {
        assertThat(printed(BREAKDOWN, table)).contains("Pagamento misto - Numerário: 20.00; A crédito: 30.00");
    }

    @Test
    void wpc1252PrintsTheBreakdownExactlyAsWritten() {
        assertThat(printed(BREAKDOWN, CodePage.WPC1252)).contains(BREAKDOWN);
    }

    @ParameterizedTest
    @EnumSource(value = CodePage.class, names = {"CP437", "CP850", "CP860", "CP858"})
    void otherTypographicCharactersFallBackToAscii(CodePage table) {
        assertThat(printed(TYPOGRAPHIC, table)).contains("Mesa 1-4 \"VIP\" 'a' obrigado...").doesNotContain("?");
    }

    @Test
    void wpc1252KeepsTheTypographicCharactersItHas() {
        assertThat(printed(TYPOGRAPHIC, CodePage.WPC1252)).contains(TYPOGRAPHIC);
    }

    private static String printed(String line, CodePage table) {
        byte[] escPos = new EscPosRenderer().render(List.of(line), null, false, table);
        return new String(escPos, table.charset());
    }
}
