package com.xavier.archfixture.tenant.orders.model.dto;

import java.util.UUID;

/** Fixture: a request DTO that names a tenant. */
public record TransferRequest(UUID tenantId, UUID orderId) {
}
