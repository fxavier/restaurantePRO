package com.xavier.backend.inventory.consumption.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A consumption exception: {@code sourceType} ORDER_LINE or ORDER_LINE_COMPONENT and its id, the catalog
 * {@code itemId} sold, the round ({@code batchId}) and order line ({@code lineId}) it comes from, and that line's
 * confirmation instant ({@code confirmedAt}).
 */
public record ExceptionView(UUID id, UUID storeId, String sourceType, UUID sourceId, UUID itemId, String kind,
                            UUID batchId, UUID lineId, Instant confirmedAt, Instant createdAt, Instant resolvedAt,
                            String resolution,
                            boolean lateRecipe) {
}
