package com.xavier.backend.orders.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * D101 as a property over a fixed-seed sample: a percent off is HALF_UP per unit and always lands in {@code [0, price]};
 * the cumulative percent against the list price (D106) is in {@code [0, 100]}; a {@link LinePricing} outside
 * {@code [0, list]} cannot exist.
 */
class EffectivePricePropertyTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final long SEED = 46L;

    @Test
    void aPercentOffIsHalfUpPerUnitAndStaysWithinZeroAndThePrice() {
        Random random = new Random(SEED);
        for (int i = 0; i < 10_000; i++) {
            Money price = Money.of(BigDecimal.valueOf(random.nextInt(1_000_000), 2), MZN);
            BigDecimal percent = BigDecimal.valueOf(1 + random.nextInt(1_000_000), 4); // (0, 100]

            Money discounted = EffectivePrice.afterPercentOff(price, percent);

            assertThat(discounted.amount()).isBetween(BigDecimal.ZERO.setScale(2), price.amount());
            assertThat(discounted.amount()).isEqualByComparingTo(price.amount()
                    .multiply(BigDecimal.ONE.subtract(percent.movePointLeft(2))).setScale(2, RoundingMode.HALF_UP));
            BigDecimal off = EffectivePrice.percentOff(price, discounted);
            assertThat(off).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
        }
    }

    @Test
    void tenPercentOfOneTwentyFiveIsOneThirteen() {
        assertThat(EffectivePrice.afterPercentOff(Money.of("1.25", MZN), BigDecimal.TEN))
                .isEqualTo(Money.of("1.13", MZN));
        assertThat(EffectivePrice.afterPercentOff(Money.of("1.25", MZN), BigDecimal.valueOf(100)))
                .isEqualTo(Money.of("0.00", MZN));
    }

    @Test
    void aPercentOutsideZeroToAHundredIsRefused() {
        assertThatThrownBy(() -> EffectivePrice.afterPercentOff(Money.of("1.00", MZN), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EffectivePrice.afterPercentOff(Money.of("1.00", MZN), new BigDecimal("100.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEffectivePriceOutsideZeroAndTheListPriceCannotBePriced() {
        Random random = new Random(SEED);
        for (int i = 0; i < 1_000; i++) {
            Money list = Money.of(BigDecimal.valueOf(random.nextInt(100_000), 2), MZN);
            Money above = list.plus(Money.of(BigDecimal.valueOf(1 + random.nextInt(1000), 2), MZN));
            Money negative = Money.of(BigDecimal.valueOf(-1 - random.nextInt(1000), 2), MZN);
            Money within = Money.of(BigDecimal.valueOf(random.nextInt(list.amount().movePointRight(2).intValue() + 1),
                    2), MZN);

            assertThat(new LinePricing(list, within, null).unitPrice()).isEqualTo(within);
            assertThatThrownBy(() -> new LinePricing(list, above, null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new LinePricing(list, negative, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
