package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.orders.LineAmounts;
import com.xavier.backend.settlement.PartitionAmounts.Share;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/** D68 (amended in S28): the anchored share rule, equal parts and fiscal's bound for allocated lines. */
class PartitionAmountsTest {

    static final Currency MZN = Currency.getInstance("MZN");
    static final BigDecimal VAT = new BigDecimal("16");

    @Test
    void aShareLeavesExactlyTheLineAmountsOfTheRemainingQuantity() {
        Money unit = money("1.00");
        LineAmounts line = LineAmounts.of(unit, new BigDecimal("3"), VAT); // 3.00 = 2.59 + 0.41

        Share first = PartitionAmounts.share(unit, VAT, new BigDecimal("3"), line.gross(), line.tax(), BigDecimal.ONE);

        LineAmounts left = LineAmounts.of(unit, new BigDecimal("2"), VAT);
        assertThat(first.gross()).isEqualTo(line.gross().minus(left.gross()));
        assertThat(first.tax()).isEqualTo(line.tax().minus(left.tax()));
        assertThat(first.net()).isEqualTo(first.gross().minus(first.tax()));
    }

    @Test
    void sharesOfAWholeLineSumToItsGrossAndTaxAndTheLastTakesTheRest() {
        Money unit = money("1.00");
        LineAmounts line = LineAmounts.of(unit, new BigDecimal("3"), VAT);
        Money gross = line.gross();
        Money tax = line.tax();
        BigDecimal left = new BigDecimal("3");
        Money grossSum = money("0.00");
        Money taxSum = money("0.00");
        for (int i = 0; i < 3; i++) {
            Share share = PartitionAmounts.share(unit, VAT, left, gross, tax, BigDecimal.ONE);
            assertThat(PartitionAmounts.withinFiscalBound(BigDecimal.ONE, unit, VAT, share.gross(), share.tax()))
                    .isTrue();
            grossSum = grossSum.plus(share.gross());
            taxSum = taxSum.plus(share.tax());
            gross = gross.minus(share.gross());
            tax = tax.minus(share.tax());
            left = left.subtract(BigDecimal.ONE);
        }
        assertThat(grossSum).isEqualTo(line.gross());
        assertThat(taxSum).isEqualTo(line.tax());
        assertThat(gross).isEqualTo(money("0.00"));
    }

    @Test
    void theLastShareOfAPoolTakesItWholeEvenWhenItIsNotTheLineAmounts() {
        Share rest = PartitionAmounts.share(money("1.00"), VAT, new BigDecimal("2"), money("2.01"), money("0.28"),
                new BigDecimal("2"));

        assertThat(rest).isEqualTo(new Share(money("2.01"), money("1.73"), money("0.28")));
    }

    @Test
    void aShareCannotTakeMoreThanTheRemainingQuantity() {
        assertThatThrownBy(() -> PartitionAmounts.share(money("1.00"), VAT, BigDecimal.ONE, money("1.00"),
                money("0.14"), new BigDecimal("2"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalPartsGiveTheLeftoverCentsToTheLowestPartNumbers() {
        assertThat(PartitionAmounts.equalParts(money("100.00"), 3))
                .containsExactly(money("33.34"), money("33.33"), money("33.33"));
    }

    @Test
    void fiscalBoundAcceptsOneMinorUnitOfGrossAndTwoOfNet() {
        Money unit = money("1.00");
        assertThat(PartitionAmounts.withinFiscalBound(BigDecimal.ONE, unit, VAT, money("1.01"), money("0.14")))
                .isTrue();
        assertThat(PartitionAmounts.withinFiscalBound(BigDecimal.ONE, unit, VAT, money("1.02"), money("0.14")))
                .isFalse();
        assertThat(PartitionAmounts.withinFiscalBound(BigDecimal.ONE, unit, VAT, money("1.00"), money("0.12")))
                .as("net 0.88 is 2 units from 0.86").isTrue();
        assertThat(PartitionAmounts.withinFiscalBound(BigDecimal.ONE, unit, VAT, money("1.00"), money("0.11")))
                .isFalse();
        assertThat(PartitionAmounts.withinFiscalBound(BigDecimal.ONE, unit, VAT, money("1.00"), money("1.01")))
                .as("negative net").isFalse();
    }

    @Test
    void anEmptyPoolIsWithinTheBoundOnlyWithNoGross() {
        Money unit = money("1.00");
        assertThat(PartitionAmounts.withinFiscalBound(BigDecimal.ZERO, unit, VAT, money("0.00"), money("0.01")))
                .as("a cent of tax drift on nothing left to invoice").isTrue();
        assertThat(PartitionAmounts.withinFiscalBound(BigDecimal.ZERO, unit, VAT, money("0.01"), money("0.00")))
                .isFalse();
    }

    static Money money(String amount) {
        return Money.of(amount, MZN);
    }
}
