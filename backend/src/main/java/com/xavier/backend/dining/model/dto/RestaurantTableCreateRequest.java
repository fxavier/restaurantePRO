package com.xavier.backend.dining.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** A new, active table in {@code storeId}; {@code areaId} must be an area of that store. */
public record RestaurantTableCreateRequest(
        @NotNull UUID storeId,
        @NotNull UUID areaId,
        @NotBlank @Size(max = 20) String code,
        @NotNull @Positive Integer seats) {
}
