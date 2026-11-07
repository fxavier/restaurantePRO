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

    /** Review case: recomputing value ÷ quantity after an OUT turned a 0.012345 cost into 0.04. */
    @Test
    void anOutNeverChangesTheAverageEvenWhenRoundingLeavesAResidue() {
        StockPosition stock = new StockPosition(new BigDecimal("1000.01"), new BigDecimal("12.3454"),
                new BigDecimal("0.012345"));

        Valued out = MovingAverage.post(stock, q("-1000"), null);

        // D113 as amended (S47): the OUT takes its share, 12.3454 × 1000 ÷ 1000.01 = 12.3453 (was round(q × avg) =
        // 12.3450); the average still does not change
        assertThat(out.value()).isEqualByComparingTo("-12.3453");
        assertThat(out.after()).isEqualTo(position("0.01", "0.0001", "0.012345"));
    }

    @Test
    void anOutThatEmptiesTheStockTakesAllItsValue() {
        StockPosition stock = new StockPosition(new BigDecimal("0.01"), new BigDecimal("0.0004"),
                new BigDecimal("0.012345"));

        Valued out = MovingAverage.post(stock, q("-0.01"), null);

        assertThat(out.value()).isEqualByComparingTo("-0.0004");
        assertThat(out.after()).isEqualTo(position("0", "0", "0.012345"));
    }

    /**
     * D113 as amended (S47 review round 4): an OUT that leaves the quantity at or above zero takes its share of the
     * value, {@code −round(V × q ÷ Q, 4)}, never more than the stock holds and with no drift bias; the unit cost
     * recorded is still the average. A 2 g bag worth 0.0100 with an average of 0.012355: each gram takes 0.0050.
     */
    @Test
    void anOutTakesItsProportionalShareOfTheValue() {
        StockPosition bag = position("2", "0.0100", "0.012355");

        Valued out = MovingAverage.post(bag, q("-1"), null);

        assertThat(out.value()).isEqualByComparingTo("-0.0050");
        assertThat(out.unitCost()).isEqualByComparingTo("0.012355");
        assertThat(out.after()).isEqualTo(position("1", "0.0050", "0.012355"));
        Valued last = MovingAverage.post(out.after(), q("-1"), null);
        assertThat(last.value()).isEqualByComparingTo("-0.0050");
        assertThat(last.after()).isEqualTo(position("0", "0", "0.012355"));
    }

    /** The reviewer's case: 1 g sales from 1000 g at 0.00015 cost 0.0002 each by q × avg; proportionally they share. */
    @Test
    void manySmallOutsShareTheValueWithoutDrift() {
        StockPosition stock = position("1000", "0.1500", "0.000150");
        BigDecimal taken = BigDecimal.ZERO;
        for (int i = 0; i < 750; i++) {
            Valued out = MovingAverage.post(stock, q("-1"), null);
            assertThat(out.value().signum()).isLessThanOrEqualTo(0);
            taken = taken.add(out.value());
            stock = out.after();
        }

        assertThat(stock.value()).isPositive();
        assertThat(stock.value().subtract(taken)).isEqualByComparingTo("0.1500");
    }

    /** Weighted-average costing: an OUT's own cost (a supplier return, a shortage) is for reference only. */
    @Test
    void anOutWithItsOwnCostIsStillValuedAtTheAverage() {
        Valued out = MovingAverage.post(position("20", "220", "11"), q("-5"), c("99"));

        assertThat(out.unitCost()).isEqualByComparingTo("11");
        assertThat(out.value()).isEqualByComparingTo("-55");
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
