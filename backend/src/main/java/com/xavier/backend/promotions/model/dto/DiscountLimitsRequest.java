package com.xavier.backend.promotions.model.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** A store's manual discount limits in percent; {@code version} is null the first time (D17). */
public record DiscountLimitsRequest(@NotNull BigDecimal freeLimitPercent, @NotNull BigDecimal maxPercent,
                                    Long version) {
}
