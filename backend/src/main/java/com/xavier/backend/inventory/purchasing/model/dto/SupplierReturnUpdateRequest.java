package com.xavier.backend.inventory.purchasing.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Replaces the reason and lines of a DRAFT supplier return (versioned, D17). */
public record SupplierReturnUpdateRequest(
        @Size(max = 500) String reason,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull ReturnLineRequest> lines,
        @NotNull Long version) {
}
