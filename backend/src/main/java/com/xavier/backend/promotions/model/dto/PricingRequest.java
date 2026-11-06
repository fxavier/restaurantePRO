package com.xavier.backend.promotions.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The lines of a round to price in {@code storeId} at {@code at} (the confirmation instant, §8.4). {@code modeCode}
 * is the order's mode name; {@code member} says the order's customer is an active loyalty member.
 */
public record PricingRequest(UUID storeId, Instant at, String modeCode, boolean member, List<PricingLine> lines) {
}
