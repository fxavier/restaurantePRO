package com.xavier.backend.inventory.purchasing.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** D125: a cost correction revalues only the share still on hand; the consumed share is a cost variance. */
class CostCorrectionSplitTest {

    @Test
    void onlyTheShareStillOnHandIsRevaluedAndTheConsumedShareIsAVariance() {
        CostCorrection split = CostCorrection.split(new BigDecimal("10"), new BigDecimal("6"), new BigDecimal("10"),
                new BigDecimal("12"));

        assertThat(split.revaluedQuantity()).isEqualByComparingTo("6");
        assertThat(split.revaluationValue()).isEqualTo(new BigDecimal("12.0000"));
        assertThat(split.varianceValue()).isEqualTo(new BigDecimal("8.0000"));
    }

    @Test
    void moreOnHandThanReceivedRevaluesOnlyWhatTheLineReceived() {
        CostCorrection split = CostCorrection.split(new BigDecimal("10"), new BigDecimal("25"), new BigDecimal("10"),
                new BigDecimal("9.5"));

        assertThat(split.revaluedQuantity()).isEqualByComparingTo("10");
        assertThat(split.revaluationValue()).isEqualTo(new BigDecimal("-5.0000"));
        assertThat(split.varianceValue()).isEqualTo(new BigDecimal("0.0000"));
    }

    @Test
    void nothingOrLessThanNothingOnHandMakesItAllAVariance() {
        CostCorrection split = CostCorrection.split(new BigDecimal("10"), new BigDecimal("-3"), new BigDecimal("10"),
                new BigDecimal("11"));

        assertThat(split.revaluedQuantity()).isEqualByComparingTo("0");
        assertThat(split.revaluationValue()).isEqualTo(new BigDecimal("0.0000"));
        assertThat(split.varianceValue()).isEqualTo(new BigDecimal("10.0000"));
    }

    @Test
    void theTwoSharesAlwaysAddUpToTheRoundedTotalDifference() {
        CostCorrection split = CostCorrection.split(new BigDecimal("3"), new BigDecimal("1"), new BigDecimal("1"),
                new BigDecimal("1.333333"));

        assertThat(split.revaluationValue()).isEqualTo(new BigDecimal("0.3333"));
        assertThat(split.revaluationValue().add(split.varianceValue())).isEqualTo(new BigDecimal("1.0000"));
    }
}
