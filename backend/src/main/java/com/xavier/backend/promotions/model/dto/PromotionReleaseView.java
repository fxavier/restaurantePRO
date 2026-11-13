package com.xavier.backend.promotions.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A promotion release; {@code version} and {@code effectiveAt} are set once it is published.
 *
 * @param validationWarnings what validation or publication left out without blocking (D147 amended 2026-11-13, e.g.
 *                           {@code PROMOTION_SCOPE_ITEM_EXCLUDED}), in the shape of an error; {@code null} before the
 *                           release was first validated
 */
public record PromotionReleaseView(UUID id, UUID storeId, String status, Long version, Instant effectiveAt,
                                   UUID publishedBy, UUID createdBy, Instant createdAt, Instant validatedAt,
                                   Instant retiredAt, List<PromotionReleaseError> validationWarnings) {
}
