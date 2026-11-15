package com.xavier.backend.tenancy.model.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** A membership of the tenant was revoked (D177): its subject is refused on its next request. */
@NamedInterface("event")
public record MembershipRevoked(UUID tenantId, UUID membershipId, Instant at) {
}
