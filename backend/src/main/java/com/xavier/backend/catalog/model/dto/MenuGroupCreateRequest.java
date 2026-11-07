package com.xavier.backend.catalog.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A component group of a MENU item (D108). Bounds and options are business rules (422 {@code MENU_GROUP_INVALID}).
 */
public record MenuGroupCreateRequest(
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull Integer minChoices,
        @NotNull Integer maxChoices,
        @NotNull Integer position,
        @NotNull @Size(max = 100) List<@Valid @NotNull ItemLineRequest> options) {
}
