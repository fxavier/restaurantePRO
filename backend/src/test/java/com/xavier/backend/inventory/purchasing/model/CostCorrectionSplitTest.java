package com.xavier.backend.inventory.purchasing.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D125 amended: a cost correction revalues only the corrected line's own share still in stock, never another
 * receipt's goods, and never takes the balance value below zero; the rest is a cost variance.
 */
class CostCorrectionSplitTest {

    static final BigDecimal TEN = new BigDecimal("10");

    @Test
    void theLinesShareStillOnHandIsRevaluedAndTheConsumedShareIsAVariance() {
        BigDecimal share = ReceiptShare.of(TEN, TEN, List.of(out("-4")));
        CostCorrection split = CostCorrection.split(TEN, share, new BigDecimal("60"), TEN, new BigDecimal("12"));

        assertThat(share).isEqualTo(new BigDecimal("6.000000"));
        assertThat(split.revaluationValue()).isEqualTo(new BigDecimal("12.0000"));
        assertThat(split.varianceValue()).isEqualTo(new BigDecimal("8.0000"));
    }

    /** Scenario A: A consumed, then B received; correcting A never touches B's stock. */
    @Test
    void aConsumedReceiptRevaluesNothingEvenWhenAnotherReceiptRefilledTheStock() {
        BigDecimal share = ReceiptShare.of(TEN, TEN, List.of(out("-10"), in("10")));
        CostCorrection split = CostCorrection.split(TEN, share, new BigDecimal("10"), TEN, BigDecimal.ZERO);

        assertThat(share).isEqualByComparingTo("0");
        assertThat(split.revaluationValue()).isEqualByComparingTo("0");
        assertThat(split.varianceValue()).isEqualTo(new BigDecimal("-100.0000"));
    }

    /** Scenario B: A 10@10 and B 10@1, 10 consumed at the average: half of A is left. */
    @Test
    void anOutTakesItsShareOfEveryReceiptInStock() {
        BigDecimal share = ReceiptShare.of(TEN, TEN, List.of(in("10"), out("-10")));
        CostCorrection split = CostCorrection.split(TEN, share, new BigDecimal("55"), TEN, BigDecimal.ZERO);

        assertThat(share).isEqualTo(new BigDecimal("5.000000"));
        assertThat(split.revaluationValue()).isEqualTo(new BigDecimal("-50.0000"));
        assertThat(split.varianceValue()).isEqualTo(new BigDecimal("-50.0000"));
    }

    @Test
    void aReturnOfTheLineTakesItsOwnQuantityAndOtherOutsTheirProportion() {
        BigDecimal share = ReceiptShare.of(TEN, new BigDecimal("20"),
                List.of(new ReceiptShare.Step(new BigDecimal("-4"), true), out("-8")));

        assertThat(share).isEqualTo(new BigDecimal("3.000000"));
    }

    @Test
    void whatCoveredANegativeStockWasNeverOnHand() {
        BigDecimal share = ReceiptShare.of(TEN, new BigDecimal("6"), List.of());

        assertThat(share).isEqualTo(new BigDecimal("6.000000"));
    }

    @Test
    void aDownwardRevaluationNeverTakesTheBalanceValueBelowZero() {
        CostCorrection split = CostCorrection.split(TEN, TEN, new BigDecimal("20"), TEN, new BigDecimal("5"));

        assertThat(split.revaluationValue()).isEqualTo(new BigDecimal("-20.0000"));
        assertThat(split.varianceValue()).isEqualTo(new BigDecimal("-30.0000"));
    }

    @Test
    void theTwoSharesAlwaysAddUpToTheRoundedTotalDifference() {
        CostCorrection split = CostCorrection.split(new BigDecimal("3"), BigDecimal.ONE, new BigDecimal("100"),
                BigDecimal.ONE, new BigDecimal("1.333333"));

        assertThat(split.revaluationValue()).isEqualTo(new BigDecimal("0.3333"));
        assertThat(split.revaluationValue().add(split.varianceValue())).isEqualTo(new BigDecimal("1.0000"));
    }

    private static ReceiptShare.Step out(String quantity) {
        return new ReceiptShare.Step(new BigDecimal(quantity), false);
    }

    private static ReceiptShare.Step in(String quantity) {
        return new ReceiptShare.Step(new BigDecimal(quantity), false);
    }
}
