package com.xavier.backend.promotions.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Replaces the stores a promotion is published to. */
public record StoresRequest(@NotNull List<@NotNull UUID> storeIds, @NotNull Long version) {
}
