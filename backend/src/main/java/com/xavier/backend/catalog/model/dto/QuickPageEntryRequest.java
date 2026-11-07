package com.xavier.backend.catalog.model.dto;

import java.util.UUID;

/** Exactly one of an item, a variant or a macro (422 otherwise). */
public record QuickPageEntryRequest(UUID itemId, UUID variantId, UUID macroId) {
}
