package com.xavier.backend.inventory.model.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** What a warehouse holds of one item; {@code minQuantity} is null when no minimum is set. */
public record StockRow(UUID warehouseId, UUID stockItemId, String code, String name, String baseUnit,
                       BigDecimal quantity, BigDecimal value, BigDecimal averageCost, BigDecimal minQuantity,
                       boolean belowMinimum) {
}
