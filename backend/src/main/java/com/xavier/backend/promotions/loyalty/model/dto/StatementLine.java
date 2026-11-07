package com.xavier.backend.promotions.loyalty.model.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A statement line: the entry and the balance after it (running Σ). */
public record StatementLine(UUID id, String kind, int points, String sourceType, UUID sourceId,
                            UUID reversesEntryId, UUID orderId, UUID storeId, LocalDate businessDate, String reason,
                            Instant occurredAt, long balance) {

    public static StatementLine of(EntryView entry, long balance) {
        return new StatementLine(entry.id(), entry.kind(), entry.points(), entry.sourceType(), entry.sourceId(),
                entry.reversesEntryId(), entry.orderId(), entry.storeId(), entry.businessDate(), entry.reason(),
                entry.occurredAt(), balance);
    }
}
