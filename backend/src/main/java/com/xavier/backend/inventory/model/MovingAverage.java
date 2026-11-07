package com.xavier.backend.inventory.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valuation by weighted moving average per warehouse and stock item (D113, P40). An IN with its own cost (a receipt,
 * a transfer IN, an opening) is valued at it, {@code round(q × cost, 4)}, or at an exact value given with it (a
 * consumption reversal, a transfer IN carrying its OUT's value); an IN without a cost is valued at the average.
 *
 * <p>An OUT records the average as its unit cost, for reference. Its value (D113 as amended, S47 review round 4):
 * <ul>
 *     <li>from a balance with {@code Q > 0} and {@code V ≥ 0} that it leaves at or above zero, its share of the value,
 *     {@code −round(V × q ÷ Q, 4)} HALF_UP: never more than the stock holds and with no drift bias; one that empties
 *     the stock takes exactly {@code −V};</li>
 *     <li>otherwise (crossing below zero, or already below), {@code −round(q × avg, 4)}.</li>
 * </ul>
 * A cost an OUT names (a supplier return at its receipt's cost, a shortage at the shipping cost) is for reference only.
 * An OUT never changes the average. When an IN brings a negative quantity back to zero or above, a REVALUATION
 * (quantity 0) moves the value so that {@code value = quantity × new cost}: the sales posted below zero at an older
 * average are corrected once the real cost is known.
 */
public final class MovingAverage {

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private MovingAverage() {
    }

    /**
     * @param quantity         signed, in the base unit
     * @param explicitUnitCost an IN's own cost, or {@code null} to value it at the current average; ignored for an
     *                         OUT, which records the average and is valued by the rule above
     */
    public static Valued post(StockPosition before, BigDecimal quantity, BigDecimal explicitUnitCost) {
        return post(before, quantity, explicitUnitCost, null);
    }

    /**
     * As {@link #post(StockPosition, BigDecimal, BigDecimal)}, with {@code explicitValue} (scale 4, 0 or more) for an IN
     * with its own cost that carries an exact value instead of {@code round(q × cost, 4)}; {@code null} otherwise.
     */
    public static Valued post(StockPosition before, BigDecimal quantity, BigDecimal explicitUnitCost,
                              BigDecimal explicitValue) {
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.signum() == 0) {
            throw new IllegalArgumentException("a stock movement moves a quantity; a revaluation uses revalue");
        }
        boolean out = quantity.signum() < 0;
        BigDecimal unitCost = (out || explicitUnitCost == null ? before.averageCost() : explicitUnitCost)
                .setScale(StockPosition.COST_SCALE, ROUNDING);
        boolean empties = out && before.quantity().add(quantity).signum() == 0;
        BigDecimal value;
        if (empties) {
            value = before.value().negate();
        } else if (!out) {
            value = explicitValue != null
                    ? explicitValue.setScale(StockPosition.VALUE_SCALE, RoundingMode.UNNECESSARY)
                    : valueOf(quantity, unitCost);
        } else if (before.quantity().signum() > 0 && before.value().signum() >= 0
                && before.quantity().add(quantity).signum() >= 0) {
            // D113 as amended (S47): the OUT takes its share of the value, never more than the stock holds and with
            // no drift bias. V >= 0 is kept as a guard: a cost correction may still leave a positive quantity with a
            // negative value, which falls back to the rule below.
            value = before.value().multiply(quantity).divide(before.quantity(), StockPosition.VALUE_SCALE, ROUNDING);
        } else {
            value = valueOf(quantity, unitCost); // crossing below zero, or already below: q × avg
        }
        StockPosition after = before.after(quantity, value, unitCost);
        boolean backFromNegative = quantity.signum() > 0 && before.quantity().signum() < 0
                && after.quantity().signum() >= 0;
        if (!backFromNegative) {
            return new Valued(unitCost, value, null, after);
        }
        BigDecimal revaluation = valueOf(after.quantity(), unitCost).subtract(after.value());
        if (revaluation.signum() == 0) {
            return new Valued(unitCost, value, null, after);
        }
        return new Valued(unitCost, value, revaluation, after.after(BigDecimal.ZERO, revaluation, unitCost));
    }

    /** A value-only movement (a cost correction, D125): the quantity stays, the average follows the value. */
    public static Valued revalue(StockPosition before, BigDecimal value) {
        BigDecimal scaled = value.setScale(StockPosition.VALUE_SCALE, RoundingMode.UNNECESSARY);
        StockPosition after = before.after(BigDecimal.ZERO, scaled, before.averageCost());
        return new Valued(before.averageCost(), scaled, null, after);
    }

    /** {@code round(quantity × unitCost, 4)} HALF_UP, signed like the quantity. */
    public static BigDecimal valueOf(BigDecimal quantity, BigDecimal unitCost) {
        return quantity.multiply(unitCost).setScale(StockPosition.VALUE_SCALE, ROUNDING);
    }

    /**
     * How one movement was valued: its unit cost and value, the REVALUATION value it calls for ({@code null} for
     * none) and the position after both.
     */
    public record Valued(BigDecimal unitCost, BigDecimal value, BigDecimal revaluation, StockPosition after) {
    }
}
