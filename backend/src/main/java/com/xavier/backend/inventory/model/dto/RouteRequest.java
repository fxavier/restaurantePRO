package com.xavier.backend.inventory.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** A consumption route of {@code storeId}: a production destination code, or null for the store's default. */
public record RouteRequest(
        @NotNull UUID storeId,
        @Size(max = 40) String destinationCode,
        @NotNull UUID warehouseId) {
}
