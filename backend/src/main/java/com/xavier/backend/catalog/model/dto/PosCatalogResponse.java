package com.xavier.backend.catalog.model.dto;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The store's current published catalog for the POS. {@code catalog} is the frozen snapshot exactly as stored
 * (shape: {@link CatalogSnapshot}).
 */
public record PosCatalogResponse(UUID releaseId, UUID storeId, long version, Instant effectiveAt, JsonNode catalog) {
}
