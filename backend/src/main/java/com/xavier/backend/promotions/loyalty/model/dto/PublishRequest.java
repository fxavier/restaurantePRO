package com.xavier.backend.promotions.loyalty.model.dto;

import jakarta.validation.constraints.NotNull;

/** Publish a DRAFT at the version the client read (409 when stale). */
public record PublishRequest(@NotNull Long version) {
}
