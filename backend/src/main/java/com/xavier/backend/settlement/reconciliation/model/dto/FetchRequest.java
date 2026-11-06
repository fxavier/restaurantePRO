package com.xavier.backend.settlement.reconciliation.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/** {@code POST /reconciliation/batches/fetch}: the statement of a provider with a {@code StatementSource}. */
public record FetchRequest(@NotNull UUID storeId, @NotBlank String methodCode, @NotNull LocalDate from,
                           @NotNull LocalDate to) {
}
