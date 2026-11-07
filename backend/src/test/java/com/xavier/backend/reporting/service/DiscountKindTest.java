package com.xavier.backend.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.orders.DiscountFacts.DiscountLine;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A discount record's kind (D105): a promotion, an offer and a loyalty reward name it; a manual change is a
 * DISCOUNT, or the REVERSAL of one when it gives money back (a negative discount), as {@code discount_record}'s
 * CHECK requires.
 */
class DiscountKindTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Test
    void eachOriginNamesItsKindAndAManualChangeThatRaisesThePriceIsAReversal() {
        assertThat(CostProjections.kindOf(line("PROMOTION", "1.00"))).isEqualTo("PROMOTION");
        assertThat(CostProjections.kindOf(line("OFFER", "12.50"))).isEqualTo("OFFER");
        assertThat(CostProjections.kindOf(line("LOYALTY", "12.50"))).isEqualTo("REWARD");
        assertThat(CostProjections.kindOf(line("MANUAL", "1.00"))).isEqualTo("DISCOUNT");
        assertThat(CostProjections.kindOf(line("MANUAL", "-1.00"))).isEqualTo("REVERSAL");
    }

    private static DiscountLine line(String origin, String discount) {
        Money price = Money.of("12.50", MZN);
        return new DiscountLine(UUID.randomUUID(), UUID.randomUUID(), "ITEM", UUID.randomUUID(), "IVA16",
                new BigDecimal("16"), price, price, price, BigDecimal.ONE, Money.of(discount, MZN), origin, null,
                null, null, UUID.randomUUID(), null, Instant.EPOCH);
    }
}
