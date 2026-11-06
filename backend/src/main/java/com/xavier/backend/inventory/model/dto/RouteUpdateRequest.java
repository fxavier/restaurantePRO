package com.xavier.backend.inventory.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Routes the destination to another warehouse of the same store (versioned, D17). */
public record RouteUpdateRequest(@NotNull UUID warehouseId, @NotNull Long version) {
}
