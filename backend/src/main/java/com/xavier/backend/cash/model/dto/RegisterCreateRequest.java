package com.xavier.backend.cash.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record RegisterCreateRequest(
        @NotNull UUID storeId,
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 120) String name) {
}
