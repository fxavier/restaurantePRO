package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * @param availability ACTIVE or INACTIVE
 * @param kind         STANDARD, MENU or FEE; {@code null} keeps the current kind
 */
public record ItemUpdateRequest(
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull UUID familyId,
        @NotNull UUID unitId,
        @NotNull UUID taxCategoryId,
        @Size(max = 30) @Pattern(regexp = ItemCreateRequest.DESTINATION_CODE) String productionDestination,
        @NotNull @Pattern(regexp = "ACTIVE|INACTIVE") String availability,
        boolean soldByWeight,
        @Pattern(regexp = "STANDARD|MENU|FEE") String kind,
        @NotNull Long version) {
}
