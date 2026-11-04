package com.xavier.backend.cash.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code active=false} deactivates the register; it is never deleted. */
public record RegisterUpdateRequest(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 120) String name,
        boolean active,
        @NotNull Long version) {
}
