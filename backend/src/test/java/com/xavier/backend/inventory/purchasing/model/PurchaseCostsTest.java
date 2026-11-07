package com.xavier.backend.inventory.purchasing.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** A pack's price becomes a cost per base unit at scale 6, HALF_UP (D125). */
class PurchaseCostsTest {

    @Test
    void aBoxOf12At120CostsTenPerUnit() {
        assertThat(PurchaseCosts.perBaseUnit(new BigDecimal("120"), new BigDecimal("12")))
                .isEqualTo(new BigDecimal("10.000000"));
    }

    @Test
    void aCostPerBaseUnitIsRoundedHalfUpAtSixDecimals() {
        assertThat(PurchaseCosts.perBaseUnit(new BigDecimal("10"), new BigDecimal("3")))
                .isEqualTo(new BigDecimal("3.333333"));
        assertThat(PurchaseCosts.perBaseUnit(new BigDecimal("50"), new BigDecimal("25000")))
                .isEqualTo(new BigDecimal("0.002000"));
        assertThat(PurchaseCosts.perBaseUnit(new BigDecimal("2"), new BigDecimal("3")))
                .isEqualTo(new BigDecimal("0.666667"));
    }
}
