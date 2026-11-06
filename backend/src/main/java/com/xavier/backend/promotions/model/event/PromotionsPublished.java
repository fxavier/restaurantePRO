package com.xavier.backend.promotions.model.event;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A store published promotion release {@code releaseId} as its {@code version} (D102): lines confirmed from now on are
 * priced from it. The POS hears it as a {@code CATALOG} change notice and re-reads its prices.
 *
 * <p>Exposed as named interface {@code event} on the type: a {@code package-info} here would break the
 * {@code eventsAreRecords} ArchUnit rule.
 */
@NamedInterface("event")
public record PromotionsPublished(UUID storeId, UUID releaseId, long version) {
}
