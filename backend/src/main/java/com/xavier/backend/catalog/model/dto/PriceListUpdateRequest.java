package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PriceListUpdateRequest(@NotBlank @Size(max = 120) String name, @NotNull Long version) {
}
