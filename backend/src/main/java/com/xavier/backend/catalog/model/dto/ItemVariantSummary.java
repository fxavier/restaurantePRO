package com.xavier.backend.catalog.model.dto;

import java.util.UUID;

public record ItemVariantSummary(UUID id, UUID itemId, String code, String name, long version) {
}
