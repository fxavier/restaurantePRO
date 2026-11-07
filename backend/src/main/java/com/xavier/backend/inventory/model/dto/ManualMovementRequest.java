package com.xavier.backend.inventory.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * A manual movement of one warehouse (waste, adjustment, return to stock; D117, D118). {@code reason} is checked by
 * the use case (422 REASON_REQUIRED) rather than here, as the PRD names it.
 */
public record ManualMovementRequest(
        @NotNull UUID warehouseId,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull ManualMovementLine> lines,
        @Size(max = 500) String reason) {
}
