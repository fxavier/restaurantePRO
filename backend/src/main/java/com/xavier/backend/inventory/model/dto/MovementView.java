package com.xavier.backend.inventory.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A posted stock movement: signed quantity (base unit, scale 6), unit cost it was valued at (scale 6) and signed
 * value (scale 4, in {@code currency}). {@code referenceUnitCost} is the cost an OUT named, kept for reference only
 * (OUTs are valued at the average). {@code actorKind} is ACCOUNT, EMPLOYEE or SYSTEM (a listener, a job).
 */
public record MovementView(UUID id, long postingSeq, UUID warehouseId, UUID storeId, UUID stockItemId, String type,
                           BigDecimal quantity, BigDecimal unitCost, BigDecimal value, String currency,
                           String sourceType, UUID sourceId, UUID sourceLineId, UUID reversesMovementId,
                           UUID recipeVersionId, UUID policyVersionId, UUID saleItemId, LocalDate businessDate,
                           Instant occurredAt, Instant postedAt, String actorKind, UUID actorId, String reason,
                           BigDecimal referenceUnitCost) {
}
