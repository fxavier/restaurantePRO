package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code POST /api/v1/pos/partitions/{id}/cancel} at the partition version the POS read. */
public record CancelPartitionRequest(@NotNull Long version, @NotBlank @Size(max = 500) String reason) {
}
