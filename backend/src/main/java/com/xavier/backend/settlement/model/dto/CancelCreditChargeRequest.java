package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/v1/pos/credit-charges/{id}/cancel}: releases a RESERVED charge whose document is not issued. */
public record CancelCreditChargeRequest(@NotBlank @Size(max = 500) String reason) {
}
