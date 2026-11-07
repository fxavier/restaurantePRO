package com.xavier.backend.inventory.model.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Go-live of inventory in a store (D122): lines confirmed at or after {@code activeFrom} are consumed. Absent, it is
 * now (omit it for now); up to 60 s in the past is taken as now, earlier is refused (422 ACTIVATION_IN_PAST).
 */
public record ActivationRequest(@NotNull UUID storeId, Instant activeFrom) {
}
