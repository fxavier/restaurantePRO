package com.xavier.backend.inventory.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valuation by weighted moving average per warehouse and stock item (D113, P40). A movement with its own cost (a
 * receipt, a transfer IN, a supplier return at the receipt's cost) is valued at it; any other is valued at the
 * current average: {@code value = round(q × cost, 4)}, signed like {@code q}. When an IN brings a negative quantity
 * back to zero or above, a REVALUATION (quantity 0) moves the value so that {@code value = quantity × new cost}: the
 * sales posted below zero at an older average are corrected once the real cost is known.
 */
public final class MovingAverage {

    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private MovingAverage() {
    }

    /**
     * @param quantity         signed, in the base unit
     * @param explicitUnitCost the movement's own cost, or {@code null} to value it at the current average
     */
    public static Valued post(StockPosition before, BigDecimal quantity, BigDecimal explicitUnitCost) {
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.signum() == 0) {
            throw new IllegalArgumentException("a stock movement moves a quantity; a revaluation uses revalue");
        }
        BigDecimal unitCost = (explicitUnitCost == null ? before.averageCost() : explicitUnitCost)
                .setScale(StockPosition.COST_SCALE, ROUNDING);
        BigDecimal value = valueOf(quantity, unitCost);
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
