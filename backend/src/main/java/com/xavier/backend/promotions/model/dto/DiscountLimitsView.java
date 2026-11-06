package com.xavier.backend.promotions.model.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** A store's working-copy discount limits; {@code version} is null while the store keeps the P44 defaults. */
public record DiscountLimitsView(UUID storeId, BigDecimal freeLimitPercent, BigDecimal maxPercent, Long version) {
}
