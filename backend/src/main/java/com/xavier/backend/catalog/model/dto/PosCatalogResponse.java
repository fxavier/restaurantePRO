package com.xavier.backend.catalog.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The store's current published catalog for the POS. {@code catalog} is the frozen snapshot exactly as stored, a tree
 * so its bytes are what was published (a version-1 document stays version 1); the contract documents it as the
 * {@link CatalogSnapshot} record that wrote it (FE-F18).
 */
public record PosCatalogResponse(UUID releaseId, UUID storeId, long version, Instant effectiveAt,
                                 @Schema(implementation = CatalogSnapshot.class) JsonNode catalog) {
}
