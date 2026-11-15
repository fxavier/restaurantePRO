package com.xavier.archfixture.tenant.orders.model.event;

import java.util.UUID;

/** Fixture: an event with its tenant first. */
public record OrderPacked(UUID tenantId, UUID orderId, UUID storeId) {
}
