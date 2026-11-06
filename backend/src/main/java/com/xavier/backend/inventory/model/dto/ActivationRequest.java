package com.xavier.backend.inventory.model.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/** Go-live of inventory in a store (D122): lines confirmed at or after {@code activeFrom} are consumed. */
public record ActivationRequest(@NotNull UUID storeId, @NotNull Instant activeFrom) {
}
