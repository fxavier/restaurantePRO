package com.xavier.backend.promotions.model.dto;

import java.time.Instant;
import java.util.UUID;

/** A promotion release; {@code version} and {@code effectiveAt} are set once it is published. */
public record PromotionReleaseView(UUID id, UUID storeId, String status, Long version, Instant effectiveAt,
                                   UUID publishedBy, UUID createdBy, Instant createdAt, Instant validatedAt,
                                   Instant retiredAt) {
}
