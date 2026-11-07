package com.xavier.backend.inventory.model.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record LevelView(UUID warehouseId, UUID stockItemId, BigDecimal minQuantity, long version) {
}
