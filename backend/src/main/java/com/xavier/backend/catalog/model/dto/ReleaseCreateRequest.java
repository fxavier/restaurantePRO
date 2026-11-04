package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** A new DRAFT release for a store. */
public record ReleaseCreateRequest(@NotNull UUID storeId) {
}
