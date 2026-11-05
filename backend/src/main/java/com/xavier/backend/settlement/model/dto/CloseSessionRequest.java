package com.xavier.backend.settlement.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * {@code POST /api/v1/pos/cash/sessions/{id}/close} at the session {@code version}: the blind count per method (a
 * method not counted counts as zero in the closing).
 */
public record CloseSessionRequest(@NotNull Long version, @NotNull List<@Valid @NotNull CountLine> counts) {
}
