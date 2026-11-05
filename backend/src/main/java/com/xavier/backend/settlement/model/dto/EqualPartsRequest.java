package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** {@code POST /api/v1/pos/orders/{id}/partitions/equal}: the residual in {@code parts} equal AMOUNT partitions. */
public record EqualPartsRequest(@NotNull Long orderVersion, @NotNull @Min(2) @Max(50) Integer parts) {
}
