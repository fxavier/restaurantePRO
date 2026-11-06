package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.NotNull;

/** {@code POST /api/v1/pos/orders/{id}/close} at the order version the POS read (stale → 409). */
public record CloseOrderRequest(@NotNull Long orderVersion) {
}
