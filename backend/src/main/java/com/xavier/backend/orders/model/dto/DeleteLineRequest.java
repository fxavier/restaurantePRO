package com.xavier.backend.orders.model.dto;

import jakarta.validation.constraints.NotNull;

/** Removes a DRAFT line; {@code version} is the line's (D17). */
public record DeleteLineRequest(@NotNull Long version) {
}
