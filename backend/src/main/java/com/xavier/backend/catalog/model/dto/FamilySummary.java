package com.xavier.backend.catalog.model.dto;

import java.util.UUID;

/** {@code parentId} is set for a subfamily; {@code version} is sent back on update (D17). */
public record FamilySummary(UUID id, String code, String name, UUID parentId, long version) {
}
