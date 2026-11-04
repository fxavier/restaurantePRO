package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** @param availability ACTIVE or INACTIVE */
public record ItemUpdateRequest(
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull UUID familyId,
        @NotNull UUID unitId,
        @NotNull UUID taxCategoryId,
        @Size(max = 30) @Pattern(regexp = ItemCreateRequest.DESTINATION_CODE) String productionDestination,
        @NotNull @Pattern(regexp = "ACTIVE|INACTIVE") String availability,
        boolean soldByWeight,
        @NotNull Long version) {
}
