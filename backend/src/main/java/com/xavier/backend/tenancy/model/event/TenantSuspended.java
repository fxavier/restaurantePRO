package com.xavier.backend.tenancy.model.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** A tenant was suspended (D181): its requests are refused and its jobs skip it; its data is untouched. */
@NamedInterface("event")
public record TenantSuspended(UUID tenantId, Instant at) {
}
