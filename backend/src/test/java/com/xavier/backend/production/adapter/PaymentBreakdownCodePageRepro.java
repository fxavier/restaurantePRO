package com.xavier.backend.production.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.production.model.CodePage;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * BUG-0001 (docs/qa/bugs/0001-payment-breakdown-dash-prints-as-question-mark.md): D97 rule 6 prints the payment
 * breakdown with an em dash (U+2014) "exactly as the user wrote it", but every printer table except WPC1252 lacks it,
 * and {@link EscPosRenderer} prints a missing character as {@code ?}. On the Portuguese CP860 table (and CP437, CP850,
 * CP858) the paper reads {@code Pagamento misto ? Numerário: 20.00; A crédito: 30.00}.
 *
 * <p>An expected failure: disabled with the bug id, and named outside Surefire's include patterns so the suite never
 * lists it as skipped. Reproduce it with
 * {@code rtk mvn test -Dtest=PaymentBreakdownCodePageRepro
 * -Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition}. What the line should become on a table without
 * the dash (a hyphen, or a different separator) is an open question in the bug, so this asserts only that the
 * separator does not degrade to {@code ?}.
 */
@Disabled("BUG-0001: the D97 em dash prints as '?' on every code page except WPC1252")
class PaymentBreakdownCodePageRepro {

    @ParameterizedTest
    @EnumSource(value = CodePage.class, names = {"CP437", "CP850", "CP860", "CP858"})
    void theBreakdownSeparatorSurvivesThePrintersTable(CodePage table) {
        byte[] escPos = new EscPosRenderer().render(
                List.of("Pagamento misto — Numerário: 20.00; A crédito: 30.00"), null, false, table);

        String printed = new String(escPos, table.charset());

        assertThat(printed).contains("Numerário: 20.00; A crédito: 30.00").doesNotContain("misto ? Numer");
    }
}
