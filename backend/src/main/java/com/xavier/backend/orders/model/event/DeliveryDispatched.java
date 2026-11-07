package com.xavier.backend.orders.model.event;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A delivery went out with its courier (D129): production prints the delivery slip
 * ({@code OrdersProductionApi.deliverySlip}) to {@code terminalId}'s printer, deduped on {@code deliveryEventId}, so a
 * re-dispatch prints a new slip and a redelivered event none.
 */
@NamedInterface("event")
public record DeliveryDispatched(UUID orderId, UUID deliveryEventId, UUID storeId, UUID terminalId) {
}
