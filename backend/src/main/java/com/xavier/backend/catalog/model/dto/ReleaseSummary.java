package com.xavier.backend.catalog.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A configuration release as the BackOffice sees it (no snapshot). {@code version} is the store's catalog
 * version, set when published (monotonic per store); {@code null} before that.
 *
 * @param status             DRAFT, VALIDATED, PUBLISHED or RETIRED
 * @param excludedItems      the active items the store excludes, which its next release leaves out (D146); computed
 *                           by validation only, {@code null} (not computed) in every other response
 * @param validationWarnings the warnings of the last validation or of the publication (D147), stored on the release;
 *                           empty before the first validation
 */
public record ReleaseSummary(UUID id, UUID storeId, String status, Long version, Instant effectiveAt,
                             UUID publishedBy, UUID createdBy, Instant createdAt, Instant validatedAt,
                             Instant retiredAt, List<ExcludedItem> excludedItems,
                             List<ReleaseValidationWarning> validationWarnings) {
}
