package com.xavier.backend.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One round as inventory consumes it (D100): an immutable fact, so a pull returns the same facts every time.
 * {@code businessDay} and {@code mode} are the order's. {@code lines} are the lines confirmed in this round, in
 * creation order, with their confirmed quantity: a later void or transfer is its own fact and never changes them,
 * and a line transferred in belongs to no round (its round is on the source order).
 */
public record BatchFacts(UUID batchId, UUID orderId, UUID storeId, LocalDate businessDay, OrderMode mode,
                         Instant confirmedAt, List<BatchLineFact> lines) {

    /**
     * A confirmed line of the round. {@code releaseId} is the catalog release it was priced from, {@code destination}
     * its production destination; {@code components} are a menu's chosen components (D108), empty otherwise.
     */
    public record BatchLineFact(UUID lineId, UUID itemId, UUID variantId, UUID releaseId, BigDecimal quantity,
                                String destination, List<ComponentFact> components) {
    }

    /** A component of a menu line (D108): {@code quantity} is for the whole line (per menu unit × menus). */
    public record ComponentFact(UUID componentId, UUID itemId, UUID variantId, BigDecimal quantity,
                                String destination) {
    }
}
