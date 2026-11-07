package com.xavier.backend.inventory.preparation.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code outputQuantity} is in the prepared item's base unit. */
public record PreparationView(UUID id, UUID storeId, UUID warehouseId, UUID recipeVersionId, UUID outputStockItemId,
                              BigDecimal outputQuantity, String status, String notes, Instant createdAt,
                              Instant confirmedAt, Instant cancelledAt, long version) {
}
