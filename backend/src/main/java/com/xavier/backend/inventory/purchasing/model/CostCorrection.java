package com.xavier.backend.inventory.purchasing.model;

import com.xavier.backend.inventory.model.MovingAverage;
import com.xavier.backend.inventory.model.StockPosition;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How a receipt line's cost correction splits (D125): the share still on hand, {@code min(received, on hand)}, is
 * revalued ({@code revalued × Δ}, a REVALUATION); the rest of {@code received × Δ} was already consumed at the old
 * cost and becomes a cost variance for the margin report. Values are HALF_UP at scale 4, so the two always add up
 * to {@code round(received × Δ, 4)}.
 *
 * @param revaluedQuantity the on-hand share (scale 6), zero when nothing is on hand
 * @param revaluationValue signed, scale 4
 * @param varianceValue    signed, scale 4
 */
public record CostCorrection(BigDecimal revaluedQuantity, BigDecimal revaluationValue, BigDecimal varianceValue) {

    /**
     * @param received     what the line brought in and still counts (received less returned), base unit
     * @param onHand       the warehouse's current quantity of the item (may be negative)
     * @param previousCost the line's cost before this correction, per base unit
     * @param newCost      the corrected cost, per base unit
     */
    public static CostCorrection split(BigDecimal received, BigDecimal onHand, BigDecimal previousCost,
                                       BigDecimal newCost) {
        BigDecimal delta = newCost.subtract(previousCost);
        BigDecimal revalued = received.min(onHand.max(BigDecimal.ZERO))
                .setScale(StockPosition.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        BigDecimal revaluation = MovingAverage.valueOf(revalued, delta);
        BigDecimal total = MovingAverage.valueOf(received, delta);
        return new CostCorrection(revalued, revaluation, total.subtract(revaluation));
    }
}
