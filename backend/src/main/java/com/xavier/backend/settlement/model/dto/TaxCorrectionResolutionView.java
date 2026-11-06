package com.xavier.backend.settlement.model.dto;

import java.time.Instant;
import java.util.UUID;

/** A pending correction's resolution (D96 rule 6): {@code action} is {@code CORRECTED}. */
public record TaxCorrectionResolutionView(UUID id, UUID adjustmentId, String action, String correctionReference,
                                          String reason, String actorKind, UUID actorId, Instant at) {
}
