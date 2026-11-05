package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.NotNull;

/** {@code POST /api/v1/pos/cash/days/{id}/operational-close} at the day {@code version}. */
public record CloseDayRequest(@NotNull Long version) {
}
