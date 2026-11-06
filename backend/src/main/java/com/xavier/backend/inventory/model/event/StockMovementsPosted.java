package com.xavier.backend.inventory.model.event;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * The stock movements of one source fact were posted (D100, frozen in S37): {@code sourceType} names the kind of
 * source (a round, a void, a receipt…) and {@code sourceId} its id; reporting pulls the movement set through
 * {@code InventoryFacts}. Published by S42, consumed by S53.
 *
 * <p>Exposed as named interface {@code event} on the type: a {@code package-info} here would break the
 * {@code eventsAreRecords} ArchUnit rule.
 */
@NamedInterface("event")
public record StockMovementsPosted(String sourceType, UUID sourceId, UUID storeId, LocalDate businessDate) {
}
