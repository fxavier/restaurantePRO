package com.xavier.backend.inventory.model.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PackView(UUID id, UUID stockItemId, String code, String name, BigDecimal quantityBase) {
}
