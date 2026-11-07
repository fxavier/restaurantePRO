package com.xavier.backend.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One posted stock movement, as reporting reads it (D131): signed quantity in the item's base unit (scale 6), unit
 * cost (scale 6) and signed value (scale 4) in {@code currency}; {@code saleItemId} is the catalog item a consumption
 * comes from (S47), {@code businessDate} the order's business day for consumption.
 */
public record MovementFact(UUID movementId, long postingSeq, UUID storeId, UUID warehouseId, UUID stockItemId,
                           String type, BigDecimal quantity, BigDecimal unitCost, BigDecimal value, String currency,
                           UUID sourceLineId, UUID reversesMovementId, UUID saleItemId, UUID recipeVersionId,
                           LocalDate businessDate, Instant occurredAt) {
}
