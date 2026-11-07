package com.xavier.backend.inventory.purchasing.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A cost correction (D125): {@code revaluationValue} revalued the {@code revaluedQuantity} still on hand; the consumed
 * rest is {@code varianceValue}. Costs per base unit.
 */
public record CostCorrectionView(UUID id, UUID receiptId, UUID receiptLineId, int seq, BigDecimal previousUnitCost,
                                 BigDecimal newUnitCost, String reason, BigDecimal revaluedQuantity,
                                 BigDecimal revaluationValue, BigDecimal varianceValue, LocalDate businessDate,
                                 Instant at) {
}
