package com.xavier.backend.inventory.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Replaces the lines and notes of a DRAFT transfer (versioned, D17). */
public record TransferUpdateRequest(
        @NotEmpty @Size(max = 200) List<@Valid @NotNull TransferLineRequest> lines,
        @Size(max = 500) String notes,
        @NotNull Long version) {
}
