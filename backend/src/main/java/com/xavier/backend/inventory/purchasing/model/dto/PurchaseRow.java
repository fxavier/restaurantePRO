package com.xavier.backend.inventory.purchasing.model.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Net purchases of one supplier or one stock item over a period (D131). {@code receivedValue}: what confirmed
 * receipts brought in; {@code returnedValue}: what confirmed supplier returns sent back, at the supplier's credit (the
 * receipt line's cost when linked, the average otherwise); {@code correctionValue}: the cost corrections dated in the
 * period; {@code netValue = received − returned + corrections}. Quantities are in the base unit, per item only.
 */
public record PurchaseRow(UUID id, String code, String name, BigDecimal receivedQuantity, BigDecimal returnedQuantity,
                          BigDecimal netQuantity, BigDecimal receivedValue, BigDecimal returnedValue,
                          BigDecimal correctionValue, BigDecimal netValue) {
}
