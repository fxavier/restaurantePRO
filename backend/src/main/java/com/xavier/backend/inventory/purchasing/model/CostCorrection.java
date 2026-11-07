package com.xavier.backend.inventory.purchasing.model;

import com.xavier.backend.inventory.model.MovingAverage;
import com.xavier.backend.inventory.model.StockPosition;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How a receipt line's cost correction splits (D125 amended): only the line's own share still in stock
 * ({@link ReceiptShare}) is revalued, {@code share × Δ} as a REVALUATION, capped so the balance value never goes below
 * zero; everything else of {@code (received − returned) × Δ} was consumed (or returned) at the old cost and becomes a
 * cost variance. Values are HALF_UP at scale 4, and the two always add up to {@code round((received − returned) × Δ,
 * 4)}.
 *
 * @param revaluedQuantity the line's share still in stock (scale 6), zero when none is left; when the value floor
 *                         caps the revaluation, the quantity it stands for ({@code revaluation ÷ Δ})
 * @param revaluationValue signed, scale 4
 * @param varianceValue    signed, scale 4
 */
public record CostCorrection(BigDecimal revaluedQuantity, BigDecimal revaluationValue, BigDecimal varianceValue) {

    /**
     * @param received     what the line brought in and still counts (received less returned), base unit
     * @param share        the line's share still in stock ({@link ReceiptShare})
     * @param balanceValue the warehouse's current value of the item
     * @param previousCost the line's cost before this correction, per base unit
     * @param newCost      the corrected cost, per base unit
     */
    public static CostCorrection split(BigDecimal received, BigDecimal share, BigDecimal balanceValue,
                                       BigDecimal previousCost, BigDecimal newCost) {
        BigDecimal delta = newCost.subtract(previousCost);
        BigDecimal revalued = share.max(BigDecimal.ZERO).min(received)
                .setScale(StockPosition.QUANTITY_SCALE, RoundingMode.HALF_UP);
        BigDecimal floor = balanceValue.max(BigDecimal.ZERO).negate().setScale(StockPosition.VALUE_SCALE,
                RoundingMode.UNNECESSARY);
        BigDecimal uncapped = MovingAverage.valueOf(revalued, delta);
        BigDecimal revaluation = uncapped.max(floor); // the value never goes below 0
        if (revaluation.compareTo(uncapped) != 0) {
            revalued = revaluation.divide(delta, StockPosition.QUANTITY_SCALE, MovingAverage.ROUNDING); // what it covers
        }
        BigDecimal total = MovingAverage.valueOf(received, delta);
        return new CostCorrection(revalued, revaluation, total.subtract(revaluation));
    }
}
