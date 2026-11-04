package com.xavier.backend.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/** D59: one rounding place per line, for tax-inclusive (gross) prices. Fiscal (S12) re-checks this exact formula. */
class LineAmountsTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Test
    void grossIsPriceTimesQuantityAndTaxIsSplitOutOfIt() {
        LineAmounts amounts = LineAmounts.of(mzn("12.50"), new BigDecimal("2"), new BigDecimal("16"));

        assertThat(amounts.gross()).isEqualTo(mzn("25.00"));
        assertThat(amounts.net()).isEqualTo(mzn("21.55")); // 25 / 1.16 = 21.5517…
        assertThat(amounts.tax()).isEqualTo(mzn("3.45"));
    }

    @Test
    void halfACentRoundsUpInBothTheGrossAndTheNet() {
        assertThat(LineAmounts.of(mzn("0.05"), new BigDecimal("0.5"), BigDecimal.ZERO).gross())
                .isEqualTo(mzn("0.03")); // 0.025

        LineAmounts amounts = LineAmounts.of(mzn("0.04"), BigDecimal.ONE, new BigDecimal("60"));

        assertThat(amounts.net()).isEqualTo(mzn("0.03")); // 0.04 / 1.6 = 0.025
        assertThat(amounts.tax()).isEqualTo(mzn("0.01"));
    }

    @Test
    void aZeroRateHasNoTax() {
        LineAmounts amounts = LineAmounts.of(mzn("10.00"), new BigDecimal("3"), BigDecimal.ZERO);

        assertThat(amounts.net()).isEqualTo(mzn("30.00"));
        assertThat(amounts.tax()).isEqualTo(mzn("0.00"));
    }

    @Test
    void aThreeDecimalWeightIsRoundedOnceAtTheGross() {
        LineAmounts amounts = LineAmounts.of(mzn("18.90"), new BigDecimal("0.457"), new BigDecimal("16"));

        assertThat(amounts.gross()).isEqualTo(mzn("8.64")); // 8.6373
        assertThat(amounts.net()).isEqualTo(mzn("7.45")); // 8.64 / 1.16 = 7.4482…
        assertThat(amounts.tax()).isEqualTo(mzn("1.19"));
    }

    @Test
    void aRateWithFourDecimalsDividesByTheExactDivisor() {
        LineAmounts amounts = LineAmounts.of(mzn("100.00"), BigDecimal.ONE, new BigDecimal("12.3456"));

        assertThat(amounts.net()).isEqualTo(mzn("89.01")); // 100 / 1.123456 = 89.0110…
        assertThat(amounts.tax()).isEqualTo(mzn("10.99"));
        assertThat(amounts.net()).isEqualTo(mzn("100.00").divide(new BigDecimal("1.123456")));
    }

    @Test
    void aFullyVoidedLineIsZero() {
        LineAmounts amounts = LineAmounts.of(mzn("12.50"), BigDecimal.ZERO, new BigDecimal("16"));

        assertThat(amounts.gross()).isEqualTo(mzn("0.00"));
        assertThat(amounts.net()).isEqualTo(mzn("0.00"));
        assertThat(amounts.tax()).isEqualTo(mzn("0.00"));
    }

    @Test
    void aNegativeQuantityOrRateIsAProgrammingError() {
        assertThatThrownBy(() -> LineAmounts.of(mzn("1.00"), new BigDecimal("-1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LineAmounts.of(mzn("1.00"), BigDecimal.ONE, new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Money mzn(String amount) {
        return Money.of(amount, MZN);
    }
}
