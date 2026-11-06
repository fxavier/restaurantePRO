package com.xavier.backend.promotions.model.dto;

import java.math.BigDecimal;

/**
 * A store's manual discount limits, in percent: up to {@code freeLimitPercent} needs no approver, above it needs
 * one, and nothing goes above {@code maxPercent}.
 */
public record DiscountLimits(BigDecimal freeLimitPercent, BigDecimal maxPercent) {
}
