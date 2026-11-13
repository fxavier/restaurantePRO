package com.xavier.backend.production.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.production.model.CutMode;
import com.xavier.backend.production.model.PrinterProfile;
import com.xavier.backend.production.model.Printout;
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
    /** Wide enough that the 53-character breakdown is not wrapped (D155 wraps a fiscal line to the width). */
    private static final PrinterProfile WIDE = new PrinterProfile(80, 64, null, CutMode.PARTIAL, 4, false, null, 100);
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
        byte[] escPos = new EscPosRenderer().render(Printout.plain(List.of(line), null), WIDE, table, false);
        return new String(escPos, table.charset());
    }
}
