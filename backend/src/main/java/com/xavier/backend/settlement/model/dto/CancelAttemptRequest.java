package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/v1/pos/payment-attempts/{id}/cancel}. */
public record CancelAttemptRequest(@NotBlank @Size(max = 500) String reason) {
}
