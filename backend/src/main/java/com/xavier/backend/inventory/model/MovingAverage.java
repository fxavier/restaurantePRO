package com.xavier.backend.inventory.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valuation by weighted moving average per warehouse and stock item (D113, P40). An IN with its own cost (a receipt,
 * a transfer IN, an opening) is valued at it; an IN without one, and every OUT, is valued at the current average:
 * {@code value = round(q × avg, 4)}, signed like {@code q}. A cost an OUT names (a supplier return at its receipt's
 * cost, a transfer shortage at the shipping cost) is for reference only and never values it, so a positive quantity
 * never carries a negative value. An OUT that empties the stock takes all its value ({@code −V}), so no residue is
 * left at zero; an OUT that leaves the quantity at or above zero takes at most the value the stock holds
 * ({@code −min(round(q × avg, 4), V)} when {@code V ≥ 0}), so while the quantity stays at or above zero the value never
 * goes below zero and an OUT's value is never positive (S47 review: a bag sold gram by gram rounds every sale up, and
 * its last sales carry less than nominal, down to 0). Below zero the rules are unchanged. An OUT never changes the average. When an IN brings a negative quantity back to zero or above, a
 * REVALUATION (quantity 0) moves the value so that {@code value = quantity × new cost}: the sales posted below zero at
 * an older average are corrected once the real cost is known.
 */
public final class MovingAverage {

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private MovingAverage() {
    }

    /**
     * @param quantity         signed, in the base unit
     * @param explicitUnitCost an IN's own cost, or {@code null} to value it at the current average; ignored for an
     *                         OUT, which is always valued at the average
     */
    public static Valued post(StockPosition before, BigDecimal quantity, BigDecimal explicitUnitCost) {
        return post(before, quantity, explicitUnitCost, null);
    }

    /**
     * As {@link #post(StockPosition, BigDecimal, BigDecimal)}, with {@code explicitValue} (scale 4) for an IN with its
     * own cost that carries an exact value instead of {@code round(q × cost, 4)}; {@code null} for the usual rule.
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
        } else {
            value = valueOf(quantity, unitCost);
            boolean staysAtOrAboveZero = before.quantity().add(quantity).signum() >= 0;
            if (staysAtOrAboveZero && before.value().signum() >= 0) {
                value = value.max(before.value().negate()); // never more than the stock holds (S47 review)
            }
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
