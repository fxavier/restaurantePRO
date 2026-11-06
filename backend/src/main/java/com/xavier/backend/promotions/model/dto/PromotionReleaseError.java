package com.xavier.backend.promotions.model.dto;

import java.util.UUID;

/**
 * One problem that blocks a promotion release: {@code code} is {@code NO_PUBLISHED_CATALOG},
 * {@code SCOPE_NOT_IN_CATALOG} or {@code PROMOTION_INVALID}; the promotion fields are null for a store-wide problem.
 */
public record PromotionReleaseError(String code, UUID promotionId, String promotionCode, String message) {
}
