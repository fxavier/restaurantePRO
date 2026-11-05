package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;

/**
 * Per tax rate of an order: its recomputed line tax minus the tax its documents carry (D68 amendment). Zero except
 * where a settled void emptied a partitioned line's pool, at most one minor unit per such line. The documents' tax is
 * authoritative; reconciliation and reporting read tax from the documents.
 */
public record TaxRoundingDifference(BigDecimal ratePercent, Money difference) {
}
