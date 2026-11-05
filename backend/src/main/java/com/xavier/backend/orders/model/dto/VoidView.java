package com.xavier.backend.orders.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A recorded void and the order after it (its new version, net quantities and due). */
public record VoidView(UUID id, UUID orderId, UUID lineId, BigDecimal quantity, String reason, boolean wasted,
                       UUID operatorEmployeeId, UUID approverEmployeeId, Instant voidedAt, OrderView order) {
}
