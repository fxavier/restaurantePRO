package com.xavier.backend.catalog.model.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A macro (D109) with its entries in order. */
public record MacroSummary(UUID id, String code, String name, List<Entry> entries, long version) {

    public record Entry(UUID itemId, UUID variantId, BigDecimal quantity) {
    }
}
