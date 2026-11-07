package com.xavier.backend.promotions.model.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The discount limits a store's <em>next</em> promotion release will freeze (working copy, D106); the limits in force
 * are those of its published release ({@code PromotionsApi.discountLimits}). {@code version} is null while the store
 * keeps the P44 defaults.
 */
public record DiscountLimitsView(UUID storeId, BigDecimal freeLimitPercent, BigDecimal maxPercent, Long version) {
}
