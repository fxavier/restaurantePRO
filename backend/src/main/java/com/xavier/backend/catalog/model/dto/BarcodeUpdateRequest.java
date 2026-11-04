package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record BarcodeUpdateRequest(@NotBlank @Size(max = 64) String code, @NotNull UUID itemId, UUID variantId,
                                   @NotNull Long version) {
}
