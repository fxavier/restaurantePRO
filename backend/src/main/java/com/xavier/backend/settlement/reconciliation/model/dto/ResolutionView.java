package com.xavier.backend.settlement.reconciliation.model.dto;

import java.time.Instant;
import java.util.UUID;

/** The decision about an entry: {@code actorKind} SYSTEM (AUTO_MATCHED, no actor), ACCOUNT or EMPLOYEE. */
public record ResolutionView(UUID id, UUID entryId, String action, String reason, String actorKind, UUID actorId,
                             Instant at) {
}
