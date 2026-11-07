package com.xavier.backend.inventory.model.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ValuationRow(UUID warehouseId, String warehouseCode, UUID stockItemId, String code, String name,
                           String baseUnit, BigDecimal quantity, BigDecimal averageCost, BigDecimal value) {
}
