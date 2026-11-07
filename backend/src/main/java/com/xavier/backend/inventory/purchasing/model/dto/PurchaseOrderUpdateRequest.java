package com.xavier.backend.inventory.purchasing.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Replaces the lines, expected date and notes of a DRAFT purchase order (versioned, D17). */
public record PurchaseOrderUpdateRequest(
        LocalDate expectedAt,
        @Size(max = 2000) String notes,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull PurchaseLineRequest> lines,
        @NotNull Long version) {
}
