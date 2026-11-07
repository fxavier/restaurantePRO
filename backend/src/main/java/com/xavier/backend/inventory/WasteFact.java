package com.xavier.backend.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A wasted void's share of one sale movement (D117 as amended): no stock moved; {@code quantity} (positive, base
 * unit) of {@code stockItemId} at the original movement's {@code unitCost} is reclassified from sale cost to waste.
 * {@code saleItemId} is the catalog item sold, {@code lineId} the round line, {@code businessDate} the order's day.
 */
public record WasteFact(UUID voidId, UUID originalMovementId, UUID storeId, UUID warehouseId, UUID stockItemId,
                        UUID saleItemId, UUID lineId, BigDecimal quantity, BigDecimal unitCost, BigDecimal value,
                        String currency, LocalDate businessDate, Instant occurredAt) {
}
