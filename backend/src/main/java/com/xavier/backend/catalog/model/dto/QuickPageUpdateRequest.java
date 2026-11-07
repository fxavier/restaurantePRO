package com.xavier.backend.catalog.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The whole new state of a quick page (its store does not change); {@code version} is the one read. */
public record QuickPageUpdateRequest(
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull @PositiveOrZero Integer position,
        @NotNull @Size(max = 200) List<@Valid @NotNull QuickPageEntryRequest> entries,
        @NotNull Long version) {
}
