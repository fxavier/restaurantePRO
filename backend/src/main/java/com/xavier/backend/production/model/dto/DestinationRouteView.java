package com.xavier.backend.production.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** One route of a store's routing table: a production destination code (as catalog items name it) → a printer. */
public record DestinationRouteView(@NotBlank @Size(max = 40) String destinationCode, @NotNull UUID printerId) {
}
