package com.xavier.backend.organization.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

/** Full replacement of a store's editable fields; {@code version} is the one the client read (D17). */
public record StoreUpdateRequest(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 64) String timezone,
        @NotNull LocalTime businessDayCutoff,
        @NotNull Boolean active,
        @NotNull Long version) {
}
