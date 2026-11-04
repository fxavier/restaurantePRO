package com.xavier.backend.catalog.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A configuration release as the BackOffice sees it (no snapshot). {@code version} is the store's catalog
 * version, set when published (monotonic per store); {@code null} before that.
 *
 * @param status DRAFT, VALIDATED, PUBLISHED or RETIRED
 */
public record ReleaseSummary(UUID id, UUID storeId, String status, Long version, Instant effectiveAt,
                             UUID publishedBy, UUID createdBy, Instant createdAt, Instant validatedAt,
                             Instant retiredAt) {
}
