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
 * A discount record's kind and origin (D105, S53): a REVERSAL is what orders says takes something back
 * ({@code reversesOrigin}), and is filed under that origin; otherwise a promotion, an offer and a loyalty reward name
 * their kind and a manual change is a DISCOUNT.
 */
class DiscountKindTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Test
    void aReversalIsWhatTakesSomethingBackAndIsFiledUnderIt() {
        assertThat(CostProjections.kindOf(line("PROMOTION", "1.00", null))).isEqualTo("PROMOTION");
        assertThat(CostProjections.kindOf(line("OFFER", "12.50", null))).isEqualTo("OFFER");
        assertThat(CostProjections.kindOf(line("LOYALTY", "12.50", null))).isEqualTo("REWARD");
        assertThat(CostProjections.kindOf(line("MANUAL", "1.00", null))).isEqualTo("DISCOUNT");
        assertThat(CostProjections.kindOf(line("MANUAL", "-12.50", "OFFER"))).isEqualTo("REVERSAL");
        assertThat(CostProjections.originOf(line("MANUAL", "-12.50", "OFFER"))).isEqualTo("OFFER");
        assertThat(CostProjections.originOf(line("MANUAL", "1.00", null))).isEqualTo("MANUAL");
    }

    @Test
    void theKindDoesNotComeFromTheSign() {
        // a manual discount the bill shows as a negative amount is still a discount unless it reverses something
        assertThat(CostProjections.kindOf(line("MANUAL", "-0.01", null))).isEqualTo("DISCOUNT");
    }

    private static DiscountLine line(String origin, String discount, String reversesOrigin) {
        Money price = Money.of("12.50", MZN);
        return new DiscountLine(UUID.randomUUID(), UUID.randomUUID(), "ITEM", UUID.randomUUID(), "IVA16",
                new BigDecimal("16"), price, price, price, BigDecimal.ONE, Money.of(discount, MZN), origin, null,
                null, null, UUID.randomUUID(), null, Instant.EPOCH, UUID.randomUUID(), BigDecimal.ONE,
                reversesOrigin);
    }
}
