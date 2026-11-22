package com.xavier.backend.production.model.dto;

import jakarta.validation.constraints.NotNull;

/** A kitchen display command (ADR 0044): {@code version} is the round's kitchen version the screen shows (stale → 409). */
public record KitchenCommandRequest(@NotNull Long version) {
}
