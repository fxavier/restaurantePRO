package com.xavier.backend.orders.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Cancels an order that bills nothing; {@code orderVersion} is the order the POS shows. */
public record CancelOrderRequest(@NotNull Long orderVersion, @NotBlank @Size(max = 200) String reason) {
}
