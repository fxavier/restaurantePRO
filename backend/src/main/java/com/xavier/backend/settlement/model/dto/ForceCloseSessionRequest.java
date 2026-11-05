package com.xavier.backend.settlement.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /api/v1/pos/cash/sessions/{id}/force-close} (P22): a manager closes a session its operator abandoned, at
 * the session {@code version}, with the blind count per method and why.
 */
public record ForceCloseSessionRequest(@NotNull Long version, @NotNull List<@Valid @NotNull CountLine> counts,
                                       @NotBlank @Size(max = 500) String reason) {
}
