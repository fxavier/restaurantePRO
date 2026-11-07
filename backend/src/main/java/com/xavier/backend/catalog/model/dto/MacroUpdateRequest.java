package com.xavier.backend.catalog.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The whole new state of a macro; {@code version} is the one read (409 when stale). */
public record MacroUpdateRequest(
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 120) String name,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull ItemLineRequest> entries,
        @NotNull Long version) {
}
