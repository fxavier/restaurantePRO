package com.xavier.backend.orders.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Names the order's customer (D130); {@code orderVersion} is the order the POS shows (stale → 409). */
public record CustomerRequest(@NotNull Long orderVersion, @NotNull UUID customerId) {
}
