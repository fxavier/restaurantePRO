package com.xavier.backend.inventory.purchasing.model.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Purchases of one supplier or one stock item over a period (D131): what confirmed receipts brought in
 * ({@code receivedValue}; {@code quantityBase} only per item), the cost corrections dated in the period, and their
 * sum ({@code value}).
 */
public record PurchaseRow(UUID id, String code, String name, BigDecimal quantityBase, BigDecimal receivedValue,
                          BigDecimal correctionValue, BigDecimal value) {
}
