package com.xavier.backend.catalog.model.dto;

import com.xavier.backend.shared.Money;
import java.util.UUID;

/** {@code variantId} is null for the item's own price. */
public record ItemPriceSummary(UUID id, UUID priceListId, UUID itemId, UUID variantId, Money price, long version) {
}
