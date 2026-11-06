package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.settlement.model.AttemptStatus;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/pos/payment-attempts/{id}/resolve}. Without {@code outcome}: ask the provider (D50 §7.4).
 * With {@code outcome} ({@code SUCCEEDED} or {@code FAILED}): an authorized manual reclassification of an UNKNOWN
 * attempt, which needs a {@code reason} and an approver in the {@code X-Approver-Code}/{@code X-Approver-Pin} headers.
 */
public record ResolveAttemptRequest(AttemptStatus outcome, @Size(max = 500) String reason) {
}
