package com.xavier.backend.tenancy.model.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** A tenant was closed (D181): suspended for good, exported, then deleted after the contract's retention. */
@NamedInterface("event")
public record TenantClosed(UUID tenantId, Instant at) {
}
