package com.xavier.backend.inventory.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * What a warehouse holds of one stock item (D113): quantity in the base unit (scale 6), value (scale 4) and the
 * moving average cost (scale 6). The balance row is this position; posting and the rebuild move it with the same
 * rule, {@link #after}.
 */
public record StockPosition(BigDecimal quantity, BigDecimal value, BigDecimal averageCost) {

    public static final int QUANTITY_SCALE = 6;
    public static final int VALUE_SCALE = 4;
    public static final int COST_SCALE = 6;

    public StockPosition {
        quantity = Objects.requireNonNull(quantity, "quantity").setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        value = Objects.requireNonNull(value, "value").setScale(VALUE_SCALE, RoundingMode.UNNECESSARY);
        averageCost = Objects.requireNonNull(averageCost, "averageCost").setScale(COST_SCALE,
                RoundingMode.UNNECESSARY);
    }

    public static StockPosition empty() {
        return new StockPosition(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * The position once a recorded movement ({@code quantity} and {@code value} signed, {@code unitCost} as posted)
     * is applied. With a positive quantity left the average is {@code value ÷ quantity}; an IN that leaves it at zero
     * or below takes its own cost (the latest cost known), and an OUT leaves the average as it was. The average never
     * goes below zero (the ledger's unit costs are never negative).
     */
    public StockPosition after(BigDecimal quantity, BigDecimal value, BigDecimal unitCost) {
        BigDecimal newQuantity = this.quantity.add(quantity);
        BigDecimal newValue = this.value.add(value);
        BigDecimal newAverage;
        if (newQuantity.signum() > 0) {
            newAverage = newValue.divide(newQuantity, COST_SCALE, MovingAverage.ROUNDING).max(BigDecimal.ZERO);
        } else if (quantity.signum() > 0) {
            newAverage = unitCost;
        } else {
            newAverage = averageCost;
        }
        return new StockPosition(newQuantity, newValue, newAverage);
    }
}
