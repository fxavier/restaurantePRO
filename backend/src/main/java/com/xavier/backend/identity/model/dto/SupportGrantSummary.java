package com.xavier.backend.identity.model.dto;

import com.xavier.backend.identity.model.SupportGrantStatus;
import java.time.Instant;
import java.util.UUID;

/** A support grant (D83). {@code status} is as of the response: EXPIRED once {@code validUntil} has passed. */
public record SupportGrantSummary(UUID id, UUID accountId, Instant validFrom, Instant validUntil, String reason,
                                  UUID grantedByAccountId, Instant revokedAt, UUID revokedByAccountId,
                                  Instant expiredAt, SupportGrantStatus status, long version) {
}
