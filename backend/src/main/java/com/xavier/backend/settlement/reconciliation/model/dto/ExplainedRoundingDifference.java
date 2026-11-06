package com.xavier.backend.settlement.reconciliation.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * D96 rule 8: for one rate of a closed order, the tax its lines recompute minus the tax its ISSUED documents carry,
 * explained by the resolved pending correction {@code adjustmentId}, whose corrective document is
 * {@code correctionReference}. Not an exception.
 */
public record ExplainedRoundingDifference(UUID orderId, BigDecimal ratePercent, Money difference, UUID adjustmentId,
                                          String correctionReference) {
}
