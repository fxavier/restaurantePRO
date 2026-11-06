package com.xavier.backend.settlement.reconciliation.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * D68 amendment: for one rate of a closed order, the tax its lines recompute minus the tax its documents carry, when
 * it is not zero and at most one minor unit per line. The documents' tax is authoritative: it is not an exception.
 */
public record ExplainedRoundingDifference(UUID orderId, BigDecimal ratePercent, Money difference) {
}
