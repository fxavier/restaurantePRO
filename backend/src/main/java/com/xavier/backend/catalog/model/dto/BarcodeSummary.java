package com.xavier.backend.catalog.model.dto;

import java.util.UUID;

/** {@code variantId} is null when the barcode identifies the item itself. */
public record BarcodeSummary(UUID id, String code, UUID itemId, UUID variantId, long version) {
}
