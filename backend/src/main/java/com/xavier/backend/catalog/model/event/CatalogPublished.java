package com.xavier.backend.catalog.model.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A store's catalog release was published (D31): from {@code effectiveAt} on the POS sells from its frozen
 * snapshot. {@code version} is monotonic per store. Read the snapshot through {@code GET /api/v1/pos/catalog}.
 *
 * <p>Exposed as named interface {@code event} on the type: a {@code package-info} here would break the
 * {@code eventsAreRecords} ArchUnit rule.
 */
@NamedInterface("event")
public record CatalogPublished(UUID storeId, UUID releaseId, long version, Instant effectiveAt) {
}
