package com.xavier.backend.catalog.model.dto;

import java.util.List;
import java.util.UUID;

/** A POS quick page (D109); {@code storeId} null = every store. Each entry is exactly one of the three ids. */
public record QuickPageSummary(UUID id, UUID storeId, String code, String name, int position, List<Entry> entries,
                               long version) {

    public record Entry(UUID itemId, UUID variantId, UUID macroId) {
    }
}
