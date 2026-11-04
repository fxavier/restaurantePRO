package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UnitCreateRequest(@NotBlank @Size(max = 20) String code, @NotBlank @Size(max = 60) String name) {
}
