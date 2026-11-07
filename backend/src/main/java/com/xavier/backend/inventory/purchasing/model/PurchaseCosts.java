package com.xavier.backend.inventory.purchasing.model;

import com.xavier.backend.inventory.model.MovingAverage;
import com.xavier.backend.inventory.model.StockPosition;
import java.math.BigDecimal;

/** Purchase quantities and costs in an item's base unit (D125: unit cost per base unit at scale 6). */
public final class PurchaseCosts {

    private PurchaseCosts() {
    }

    /** The cost per base unit of a purchase unit (a pack of {@code baseUnitsPerUnit}) costing {@code unitCost}. */
    public static BigDecimal perBaseUnit(BigDecimal unitCost, BigDecimal baseUnitsPerUnit) {
        return unitCost.divide(baseUnitsPerUnit, StockPosition.COST_SCALE, MovingAverage.ROUNDING);
    }
}
