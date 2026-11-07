package com.xavier.backend.orders;

import java.time.Instant;
import java.util.UUID;

/**
 * What settlement shows of a DELIVERY order (D129): its status, the courier who has it (or had it last), when it was
 * promised and its zone, so the cashier sees what each courier still owes.
 */
public record DeliverySummary(DeliveryStatus status, UUID courierEmployeeId, Instant promisedAt, String zoneCode) {
}
