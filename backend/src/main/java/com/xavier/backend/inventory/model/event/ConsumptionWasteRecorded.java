package com.xavier.backend.inventory.model.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A wasted void reclassified part of a sale's cost from sale to waste (D117 as amended, S47): {@code quantity}
 * (positive, base unit) of {@code stockItemId} in {@code warehouseId}, worth {@code value} at the original sale
 * movement's cost. No stock moved, so no {@link StockMovementsPosted} fires for it; reporting (S53) reads the record
 * through {@code InventoryFacts.wasteOf(voidId)}. One event per sale movement reclassified, published in the
 * recording transaction. {@code occurredAt} is the void's instant, {@code businessDate} the order's day.
 */
@NamedInterface("event")
public record ConsumptionWasteRecorded(UUID voidId, UUID originalMovementId, UUID storeId, UUID warehouseId,
                                       UUID stockItemId, BigDecimal quantity, BigDecimal value, String currency,
                                       LocalDate businessDate, Instant occurredAt) {
}
