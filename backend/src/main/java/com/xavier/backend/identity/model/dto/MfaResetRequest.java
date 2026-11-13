package com.xavier.backend.identity.model.dto;

import jakarta.validation.constraints.Size;

/** An administrator's reset of another account's second factor (D143); a blank reason is a 422 REASON_REQUIRED. */
public record MfaResetRequest(@Size(max = 500) String reason) {
}
