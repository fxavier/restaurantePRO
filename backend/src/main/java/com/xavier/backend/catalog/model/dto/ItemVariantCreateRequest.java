package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ItemVariantCreateRequest(@NotBlank @Size(max = 30) String code, @NotBlank @Size(max = 120) String name) {
}
