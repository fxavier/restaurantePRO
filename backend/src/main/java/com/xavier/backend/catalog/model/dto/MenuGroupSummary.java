package com.xavier.backend.catalog.model.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A component group of the MENU item {@code itemId}, options in order. */
public record MenuGroupSummary(UUID id, UUID itemId, String code, String name, int minChoices, int maxChoices,
                               int position, List<Option> options, long version) {

    public record Option(UUID itemId, UUID variantId, BigDecimal quantity) {
    }
}
