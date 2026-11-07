package com.xavier.backend.dining.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A reservation as the POS calendar and the BackOffice show it (D126). {@code tableIds} are informative. */
public record ReservationView(UUID id, UUID storeId, Instant startsAt, Integer durationMinutes, int partySize,
                              String contactName, String contactPhoneDigits, UUID customerId, String notes,
                              ReservationStatus status, List<UUID> tableIds, UUID occupationId, Instant seatedAt,
                              Instant cancelledAt, String cancelReason, Instant noShowAt, String noShowByActorKind,
                              Instant createdAt, Long version) {
}
