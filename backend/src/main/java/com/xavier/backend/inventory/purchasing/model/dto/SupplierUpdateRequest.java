package com.xavier.backend.inventory.purchasing.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Replaces a supplier's data (versioned, D17); {@code active = false} deactivates it. */
public record SupplierUpdateRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 40) String taxId,
        @Email @Size(max = 200) String email,
        @Size(max = 40) String phone,
        @Size(max = 2000) String notes,
        @NotNull Boolean active,
        @NotNull Long version) {
}
