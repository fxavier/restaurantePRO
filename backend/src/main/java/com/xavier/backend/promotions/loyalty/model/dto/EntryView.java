package com.xavier.backend.promotions.loyalty.model.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A points ledger entry: signed whole points, its source, and the entry it reverses (or null). */
public record EntryView(UUID id, String kind, int points, String sourceType, UUID sourceId, UUID reversesEntryId,
                        UUID orderId, UUID storeId, LocalDate businessDate, String reason, Instant occurredAt) {
}
