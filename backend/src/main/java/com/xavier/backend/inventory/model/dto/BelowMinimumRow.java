package com.xavier.backend.inventory.model.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code missing} = minimum − quantity. */
public record BelowMinimumRow(UUID warehouseId, String warehouseCode, UUID stockItemId, String code, String name,
                              String baseUnit, BigDecimal quantity, BigDecimal minQuantity, BigDecimal missing,
                              BigDecimal value) {
}
