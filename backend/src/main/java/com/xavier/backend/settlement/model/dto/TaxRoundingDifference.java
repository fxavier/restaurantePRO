package com.xavier.backend.settlement.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;

/**
 * Per tax rate of an order (D96 rule 7): {@code difference} is Σ of the tax its emptied pools keep (a settled void
 * that emptied a partitioned line's pool, at most one minor unit per such line), the order's recomputed tax minus Σ
 * its shares' and pools'. {@code absorbed} is what residual draft lines took, {@code pendingCorrection} what open
 * pending corrections hold and {@code corrected} what resolved ones held. {@code unassigned} is the rest: non-zero
 * only on an OPEN order whose residual document is not drafted yet.
 */
public record TaxRoundingDifference(BigDecimal ratePercent, Money difference, Money absorbed,
                                    Money pendingCorrection, Money corrected) {

    @JsonProperty("unassigned")
    public Money unassigned() {
        return difference.minus(absorbed).minus(pendingCorrection).minus(corrected);
    }
}
