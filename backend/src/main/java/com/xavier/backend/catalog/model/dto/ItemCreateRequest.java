package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * A new item, ACTIVE. {@code productionDestination} is an upper-case code (KITCHEN, BAR…) or null; {@code kind}
 * STANDARD (also when null), MENU or FEE.
 */
public record ItemCreateRequest(
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull UUID familyId,
        @NotNull UUID unitId,
        @NotNull UUID taxCategoryId,
        @Size(max = 30) @Pattern(regexp = ItemCreateRequest.DESTINATION_CODE) String productionDestination,
        boolean soldByWeight,
        @Pattern(regexp = "STANDARD|MENU|FEE") String kind) {

    static final String DESTINATION_CODE = "^[A-Z][A-Z0-9_]*$";
}
