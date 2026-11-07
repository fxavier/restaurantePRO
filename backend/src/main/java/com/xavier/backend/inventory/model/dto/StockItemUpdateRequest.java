package com.xavier.backend.inventory.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A versioned edit (D17): a stale {@code version} is a 409. The base unit and kind are fixed at creation. */
public record StockItemUpdateRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 160) String name,
        boolean active,
        @NotNull Long version) {
}
