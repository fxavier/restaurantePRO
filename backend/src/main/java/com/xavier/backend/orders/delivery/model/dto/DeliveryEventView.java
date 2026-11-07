package com.xavier.backend.orders.delivery.model.dto;

import com.xavier.backend.orders.DeliveryStatus;
import java.time.Instant;
import java.util.UUID;

public record DeliveryEventView(UUID id, DeliveryStatus fromStatus, DeliveryStatus toStatus, Instant at,
                                UUID actorEmployeeId, UUID courierEmployeeId, String reason) {
}
