package com.xavier.backend.cash.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/** Opens the operator's session; the store, terminal and operator come from the POS session (D39). */
public record OpenSessionRequest(@NotNull UUID registerId, @NotNull @PositiveOrZero BigDecimal openingFloat) {
}
