package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** {@code variantId}, when set, must be a variant of {@code itemId}. */
public record BarcodeCreateRequest(@NotBlank @Size(max = 64) String code, @NotNull UUID itemId, UUID variantId) {
}
