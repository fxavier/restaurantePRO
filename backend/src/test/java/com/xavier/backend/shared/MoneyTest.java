package com.xavier.backend.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency MZN = Currency.getInstance("MZN");
    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void addsAmountsOfTheSameCurrency() {
        Money total = Money.of("10.50", MZN).plus(Money.of("2.25", MZN));

        assertThat(total).isEqualTo(Money.of(new BigDecimal("12.75"), MZN));
    }

    @Test
    void multiplyingRoundsHalfUpToTheCurrencyMinorUnit() {
        Money unitPrice = Money.of("0.25", MZN);

        assertThat(unitPrice.times(new BigDecimal("0.5"))).isEqualTo(Money.of("0.13", MZN));
        assertThat(unitPrice.times(new BigDecimal("-0.5"))).isEqualTo(Money.of("-0.13", MZN));
        assertThat(Money.of("3", Currency.getInstance("JPY")).times(new BigDecimal("0.5")))
                .isEqualTo(Money.of("2", Currency.getInstance("JPY")));
    }

    @Test
    void dividingRoundsHalfUpToTheCurrencyMinorUnit() {
        // D59: net = gross / (1 + rate/100), the only rounding point next to times
        assertThat(Money.of("100.00", MZN).divide(new BigDecimal("1.16"))).isEqualTo(Money.of("86.21", MZN));
        assertThat(Money.of("10.00", MZN).divide(new BigDecimal("3"))).isEqualTo(Money.of("3.33", MZN));
        assertThat(Money.of("0.05", MZN).divide(new BigDecimal("2"))).isEqualTo(Money.of("0.03", MZN));
        assertThat(Money.of("-0.05", MZN).divide(new BigDecimal("2"))).isEqualTo(Money.of("-0.03", MZN));
        assertThat(Money.of("5", Currency.getInstance("JPY")).divide(new BigDecimal("2")))
                .isEqualTo(Money.of("3", Currency.getInstance("JPY")));
    }

    @Test
    void rejectsDividingByZero() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of("1.00", MZN).divide(BigDecimal.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of("1.00", MZN).divide(new BigDecimal("0.000")));
    }

    @Test
    void rejectsAmountsWithMorePrecisionThanTheCurrencyAllows() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of("0.125", MZN));
    }

    @Test
    void allocatingIntoEqualPartsSumsToTheTotal() {
        assertThat(Money.of("100.00", MZN).allocate(3))
                .containsExactly(Money.of("33.34", MZN), Money.of("33.33", MZN), Money.of("33.33", MZN));
        assertThat(Money.of("-100.00", MZN).allocate(3))
                .containsExactly(Money.of("-33.34", MZN), Money.of("-33.33", MZN), Money.of("-33.33", MZN));
        assertThat(Money.of("0.02", MZN).allocate(3))
                .containsExactly(Money.of("0.01", MZN), Money.of("0.01", MZN), Money.of("0.00", MZN));
    }

    @Test
    void allocatingByWeightGivesLeftoverCentsToTheLargestRemainders() {
        List<Money> parts = Money.of("0.99", MZN).allocate(weights("50", "30", "20"));

        assertThat(parts).containsExactly(Money.of("0.49", MZN), Money.of("0.30", MZN), Money.of("0.20", MZN));
    }

    @Test
    void weightedAllocationAlwaysSumsToTheTotal() {
        Money total = Money.of("1234.57", MZN);
        List<Money> parts = total.allocate(weights("1.5", "0", "2.25", "7", "0.333"));

        assertThat(parts).hasSize(5);
        assertThat(parts.get(1)).isEqualTo(Money.of("0", MZN));
        assertThat(parts.stream().reduce(Money.of("0", MZN), Money::plus)).isEqualTo(total);
    }

    @Test
    void rejectsWeightsThatCannotSplitAnAmount() {
        Money total = Money.of("1.00", MZN);

        assertThatIllegalArgumentException().isThrownBy(() -> total.allocate(List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> total.allocate(weights("0", "0")));
        assertThatIllegalArgumentException().isThrownBy(() -> total.allocate(weights("2", "-1")));
    }

    private static List<BigDecimal> weights(String... values) {
        return Arrays.stream(values).map(BigDecimal::new).toList();
    }

    @Test
    void rejectsAllocatingIntoNoParts() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of("1.00", MZN).allocate(0));
    }

    @Test
    void rejectsMixingCurrencies() {
        Money meticais = Money.of("10.00", MZN);
        Money euros = Money.of("10.00", EUR);

        assertThatIllegalArgumentException().isThrownBy(() -> meticais.plus(euros)).withMessageContaining("EUR");
        assertThatIllegalArgumentException().isThrownBy(() -> meticais.minus(euros));
    }
}
