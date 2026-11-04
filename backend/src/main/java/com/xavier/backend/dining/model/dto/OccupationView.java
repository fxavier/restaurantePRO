package com.xavier.backend.dining.model.dto;

import java.time.Instant;
import java.util.UUID;

/** An open table occupation as {@code DiningApi} returns it. */
public record OccupationView(UUID id, UUID storeId, UUID tableId, UUID orderId, int guests, Instant openedAt,
                             UUID openedByEmployeeId, Instant subtotalPrintedAt) {
}
