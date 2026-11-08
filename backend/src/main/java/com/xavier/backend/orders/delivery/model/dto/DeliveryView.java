package com.xavier.backend.orders.delivery.model.dto;

import com.xavier.backend.orders.DeliveryStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A delivery with its address snapshot and its history, oldest transition first. */
public record DeliveryView(UUID orderId, long orderNumber, UUID storeId, UUID customerId, String phone,
                           AddressRequest address, UUID zoneId, String zoneCode, DeliveryStatus status,
                           UUID courierEmployeeId, Instant promisedAt, Instant dispatchedAt, Instant deliveredAt,
                           long version, List<DeliveryEventView> history) {
}
