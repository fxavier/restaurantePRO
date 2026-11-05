package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One billable line of an order and what covers it (D70): its net quantity and gross, the quantity in LIVE ITEMS
 * partitions, and the quantity on ISSUED and on DRAFT documents (partition, residual or R2 documents).
 */
public record LineCoverage(UUID lineId, String description, BigDecimal netQty, Money gross, BigDecimal partitioned,
                           BigDecimal issued, BigDecimal drafted) {
}
