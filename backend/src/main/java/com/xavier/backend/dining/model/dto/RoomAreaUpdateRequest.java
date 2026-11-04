package com.xavier.backend.dining.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Full replacement of an area's editable fields; {@code active=false} deactivates it. The store never changes.
 * {@code version} is the one the client read (D17).
 */
public record RoomAreaUpdateRequest(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull Boolean active,
        @NotNull Long version) {
}
