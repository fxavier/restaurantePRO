package com.xavier.backend.dining.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** A new, active room area in {@code storeId}. */
public record RoomAreaCreateRequest(
        @NotNull UUID storeId,
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 120) String name) {
}
