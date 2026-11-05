package com.xavier.backend.settlement.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.shared.Money;
import java.util.Currency;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The financial state is derived from the due and the payments, never stored (spec §8.3). */
class FinancialStateTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @ParameterizedTest
    @CsvSource({
            "25.00, 0.00, UNSETTLED",
            "25.00, 0.01, PARTIALLY_SETTLED",
            "25.00, 24.99, PARTIALLY_SETTLED",
            "25.00, 25.00, SETTLED",
            "0.00, 0.00, SETTLED"
    })
    void theStateFollowsFromDueAndPaid(String due, String paid, FinancialState expected) {
        assertThat(FinancialState.of(Money.of(due, MZN), Money.of(paid, MZN))).isEqualTo(expected);
    }
}
