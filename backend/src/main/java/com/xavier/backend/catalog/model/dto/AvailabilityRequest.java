package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Marks the item (or {@code variantId}) sold out or available again in the POS session's store. */
public record AvailabilityRequest(UUID variantId, @NotNull Boolean soldOut, @Size(max = 200) String reason) {
}
