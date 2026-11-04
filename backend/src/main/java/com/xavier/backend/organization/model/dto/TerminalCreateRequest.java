package com.xavier.backend.organization.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Registers a terminal in a store; {@code code} is unique within the store. */
public record TerminalCreateRequest(
        @NotNull UUID storeId,
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 120) String name) {
}
