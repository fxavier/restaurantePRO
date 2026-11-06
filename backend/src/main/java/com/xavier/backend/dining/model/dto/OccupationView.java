package com.xavier.backend.dining.model.dto;

import java.time.Instant;
import java.util.UUID;

/** An open table occupation as {@code DiningApi} returns it; {@code tableCode} is its table's code. */
public record OccupationView(UUID id, UUID storeId, UUID tableId, String tableCode, UUID orderId, int guests,
                             Instant openedAt,
                             UUID openedByEmployeeId, Instant subtotalPrintedAt) {
}
