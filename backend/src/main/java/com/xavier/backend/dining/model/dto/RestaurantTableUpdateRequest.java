package com.xavier.backend.dining.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Full replacement of a table's editable fields; {@code active=false} deactivates it. It may move to another
 * area of the same store; the store never changes. {@code version} is the one the client read (D17).
 */
public record RestaurantTableUpdateRequest(
        @NotNull UUID areaId,
        @NotBlank @Size(max = 20) String code,
        @NotNull @Positive Integer seats,
        @NotNull Boolean active,
        @NotNull Long version) {
}
