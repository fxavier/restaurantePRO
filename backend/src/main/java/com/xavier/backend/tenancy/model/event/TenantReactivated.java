package com.xavier.backend.tenancy.model.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** A suspended tenant was reactivated (D181). */
@NamedInterface("event")
public record TenantReactivated(UUID tenantId, Instant at) {
}
