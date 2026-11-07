package com.xavier.backend.inventory.model.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Rotation of one item over the period (D131): cost of consumption (sales net of reversals) ÷ average stock value
 * ((opening + closing) ÷ 2, by business day). {@code rotation} is null when the average value is not positive.
 */
public record RotationRow(UUID stockItemId, String code, String name, BigDecimal consumptionCost,
                          BigDecimal openingValue, BigDecimal closingValue, BigDecimal averageValue,
                          BigDecimal rotation, BigDecimal value) {
}
