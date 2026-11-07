package com.xavier.backend.inventory.model.dto;

import com.xavier.backend.inventory.model.NegativePolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A versioned edit (D17); the store of a warehouse never changes. */
public record WarehouseUpdateRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 160) String name,
        @NotNull NegativePolicy negativePolicy,
        boolean active,
        @NotNull Long version) {
}
