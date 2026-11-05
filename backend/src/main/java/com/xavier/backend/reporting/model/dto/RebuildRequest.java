package com.xavier.backend.reporting.model.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/** Rebuild the store's projections over the business dates {@code [from, to]} (D84). */
public record RebuildRequest(@NotNull UUID storeId, @NotNull LocalDate from, @NotNull LocalDate to) {
}
