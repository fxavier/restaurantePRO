package com.xavier.backend.inventory.purchasing.model;

import com.xavier.backend.inventory.model.MovingAverage;
import com.xavier.backend.inventory.model.StockPosition;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * How much of one receipt line is still in stock under the moving average (D125 amended): the line's quantity, less
 * what the warehouse already owed below zero when it arrived, then reduced by every later OUT of the same item and
 * warehouse in posting order. An OUT of {@code q} from {@code Q} on hand takes {@code q ÷ Q} of every unit there, so
 * of this line too; a return linked to the line takes its own quantity from it. INs and revaluations leave it.
 */
public final class ReceiptShare {

    private ReceiptShare() {
    }

    /**
     * @param received             the receipt line's quantity, base unit
     * @param quantityAfterReceipt the warehouse's quantity right after the receipt's movement
     * @param later                the item's movements in the warehouse after the receipt's, in posting order
     * @return the line's share still in stock, scale 6, never below zero
     */
    public static BigDecimal of(BigDecimal received, BigDecimal quantityAfterReceipt, List<Step> later) {
        BigDecimal share = received.min(quantityAfterReceipt.max(BigDecimal.ZERO));
        BigDecimal onHand = quantityAfterReceipt;
        for (Step step : later) {
            BigDecimal quantity = step.quantity();
            if (quantity.signum() < 0) {
                BigDecimal out = quantity.negate();
                if (step.ownReturn()) {
                    share = share.subtract(out).max(BigDecimal.ZERO);
                } else if (onHand.signum() <= 0) {
                    share = BigDecimal.ZERO;
                } else {
                    BigDecimal kept = BigDecimal.ONE.subtract(out.divide(onHand, MathContext.DECIMAL128))
                            .max(BigDecimal.ZERO);
                    share = share.multiply(kept, MathContext.DECIMAL128);
                }
            }
            onHand = onHand.add(quantity);
        }
        return share.min(onHand.max(BigDecimal.ZERO)).setScale(StockPosition.QUANTITY_SCALE, MovingAverage.ROUNDING);
    }

    /** One later movement: its signed quantity, and whether it is a return of this very receipt line. */
    public record Step(BigDecimal quantity, boolean ownReturn) {
    }
}
