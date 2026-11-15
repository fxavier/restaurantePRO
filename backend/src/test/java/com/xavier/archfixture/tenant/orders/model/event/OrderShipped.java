package com.xavier.archfixture.tenant.orders.model.event;

import java.util.UUID;

/** Fixture: an event without a tenant. */
public record OrderShipped(UUID orderId, UUID storeId) {
}
