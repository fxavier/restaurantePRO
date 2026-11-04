package com.xavier.backend.orders.model.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Part or all of a confirmed line was voided (published by S14): production prints a VOID ticket for {@code quantity}
 * of {@code line} on its destination.
 */
public record OrderLinesVoided(UUID orderId, UUID voidId, UUID storeId, UUID terminalId, long orderNumber,
                               String tableCode, Line line, BigDecimal quantity, String reason, Instant at) {
}
