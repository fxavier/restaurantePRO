package com.xavier.backend.promotions.loyalty.model.dto;

import jakarta.validation.constraints.Size;

/** Suspend or resume an account; a blank reason is a 422 {@code REASON_REQUIRED}. */
public record ReasonRequest(@Size(max = 500) String reason) {
}
