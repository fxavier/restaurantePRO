package com.xavier.backend.catalog.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The whole new state of a menu group, replacing its options; {@code version} is the one read (409 when stale).
 */
public record MenuGroupUpdateRequest(
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull Integer minChoices,
        @NotNull Integer maxChoices,
        @NotNull Integer position,
        @NotNull @Size(max = 100) List<@Valid @NotNull ItemLineRequest> options,
        @NotNull Long version) {
}
