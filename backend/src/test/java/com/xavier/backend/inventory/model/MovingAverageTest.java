package com.xavier.backend.inventory.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.inventory.model.MovingAverage.Valued;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The weighted moving average (D113, P40): INs carry their own cost, OUTs are valued at the current average
 * ({@code value = −round(q × avg, 4)}), an IN that brings a negative quantity back to zero or above posts a
 * REVALUATION so that {@code value = quantity × new cost}. Costs at scale 6, values at scale 4, HALF_UP.
 */
class MovingAverageTest {

    @Test
    void twoReceiptsAt10And12AverageTo11() {
        Valued first = MovingAverage.post(StockPosition.empty(), q("10"), c("10.00"));
        Valued second = MovingAverage.post(first.after(), q("10"), c("12.00"));

        assertThat(first.value()).isEqualByComparingTo("100.0000");
        assertThat(second.value()).isEqualByComparingTo("120.0000");
        assertThat(second.after()).isEqualTo(position("20", "220", "11"));
        assertThat(second.revaluation()).isNull();
    }

    @Test
    void anOutIsValuedAtTheCurrentAverageAndLeavesItUnchanged() {
        StockPosition stock = position("20", "220", "11");

        Valued out = MovingAverage.post(stock, q("-5"), null);

        assertThat(out.unitCost()).isEqualByComparingTo("11");
        assertThat(out.value()).isEqualByComparingTo("-55.0000");
        assertThat(out.after()).isEqualTo(position("15", "165", "11"));
    }

    @Test
    void anInBringingANegativeQuantityBackAboveZeroRevaluesToTheNewCost() {
        StockPosition stock = position("4", "40", "10");
        Valued sale = MovingAverage.post(stock, q("-6"), null);
        assertThat(sale.after()).isEqualTo(position("-2", "-20", "10"));

        Valued receipt = MovingAverage.post(sale.after(), q("10"), c("12.00"));

        assertThat(receipt.value()).isEqualByComparingTo("120.0000");
        assertThat(receipt.revaluation()).isEqualByComparingTo("-4.0000"); // 8 × 12 = 96, the ledger had 100
        assertThat(receipt.after()).isEqualTo(position("8", "96", "12"));
    }

    @Test
    void anInThatLeavesTheQuantityNegativeTakesItsCostAsTheAverageAndDoesNotRevalue() {
        Valued receipt = MovingAverage.post(position("-5", "-50", "10"), q("2"), c("12.00"));

        assertThat(receipt.revaluation()).isNull();
        assertThat(receipt.after()).isEqualTo(position("-3", "-26", "12"));
    }

    @Test
    void anInWithoutACostIsValuedAtTheAverage() {
        Valued back = MovingAverage.post(position("15", "165", "11"), q("1"), null);

        assertThat(back.unitCost()).isEqualByComparingTo("11");
        assertThat(back.after()).isEqualTo(position("16", "176", "11"));
    }

    @Test
    void valuesRoundHalfUpAtScale4AndTheAverageAtScale6() {
        Valued receipt = MovingAverage.post(StockPosition.empty(), q("3"), c("1.111111"));
        assertThat(receipt.value()).isEqualTo(new BigDecimal("3.3333"));
        assertThat(receipt.after().averageCost()).isEqualTo(new BigDecimal("1.111100"));

        assertThat(MovingAverage.valueOf(q("1"), c("0.000050"))).isEqualTo(new BigDecimal("0.0001"));
        assertThat(MovingAverage.valueOf(q("-1"), c("0.000050"))).isEqualTo(new BigDecimal("-0.0001"));
        assertThat(MovingAverage.valueOf(q("2"), c("1.000025"))).isEqualTo(new BigDecimal("2.0001"));
    }

    @Test
    void aRevaluationMovesValueOnly() {
        Valued revaluation = MovingAverage.revalue(position("8", "96", "12"), new BigDecimal("8.0000"));

        assertThat(revaluation.value()).isEqualByComparingTo("8.0000");
        assertThat(revaluation.after()).isEqualTo(position("8", "104", "13"));
    }

    @Test
    void replayingTheRecordedMovementsGivesThePostedPosition() {
        Valued a = MovingAverage.post(StockPosition.empty(), q("3"), c("1.111111"));
        Valued b = MovingAverage.post(a.after(), q("-5"), null);
        Valued c = MovingAverage.post(b.after(), q("7"), c("2.5"));

        StockPosition replayed = StockPosition.empty()
                .after(q("3"), a.value(), a.unitCost())
                .after(q("-5"), b.value(), b.unitCost())
                .after(q("7"), c.value(), c.unitCost())
                .after(BigDecimal.ZERO, c.revaluation(), c.unitCost());

        assertThat(replayed).isEqualTo(c.after());
    }

    private static StockPosition position(String quantity, String value, String average) {
        return new StockPosition(new BigDecimal(quantity), new BigDecimal(value), new BigDecimal(average));
    }

    private static BigDecimal q(String quantity) {
        return new BigDecimal(quantity);
    }

    private static BigDecimal c(String cost) {
        return new BigDecimal(cost);
    }
}
