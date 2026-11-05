package com.xavier.backend.orders.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Moves a DINE_IN order to the free table {@code tableId}; {@code orderVersion} is the order the POS shows. */
public record TableMoveRequest(@NotNull Long orderVersion, @NotNull UUID tableId) {
}
