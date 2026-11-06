package com.xavier.backend.settlement.reconciliation.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code POST /reconciliation/entries/{id}/resolutions}: a person's decision (AUTO_MATCHED is the system's). */
public record ResolutionRequest(
        @NotNull @Pattern(regexp = "ACCEPTED|REFUNDED_AT_PROVIDER|ESCALATED") String action,
        @NotBlank @Size(max = 500) String reason) {
}
