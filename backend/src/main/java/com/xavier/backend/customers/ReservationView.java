package com.xavier.backend.customers;

import com.xavier.backend.shared.Money;
import java.util.UUID;

/** The limit held for one credit charge; {@code status} is ACTIVE, CONSUMED or RELEASED. */
public record ReservationView(UUID id, UUID customerId, UUID chargeId, Money amount, String status) {
}
