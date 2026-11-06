package com.xavier.backend.settlement.reconciliation.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /reconciliation/tax-corrections/{id}/resolutions} (D96 rule 6): the corrective document the market's
 * correction process produced, and why.
 */
public record TaxCorrectionRequest(@NotBlank @Size(max = 100) String correctionReference,
                                   @NotBlank @Size(max = 500) String reason) {
}
