package com.xavier.archfixture.tenant.orders.model.event;

import java.util.UUID;

/** Fixture: a tenant that is not the first component. */
public record LateTenant(UUID orderId, UUID tenantId) {
}
