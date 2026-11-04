package com.xavier.backend.orders.model.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Confirms a round (D50 §7.1). {@code orderVersion} is the order the POS shows (stale → 409); {@code catalogVersion}
 * the catalog it priced the drafts from (another published version → 409 {@code CATALOG_CHANGED}).
 */
public record ConfirmBatchRequest(
        @NotNull Long orderVersion,
        @NotNull Long catalogVersion,
        @NotEmpty @Size(max = 200) List<@NotNull UUID> lineIds) {
}
