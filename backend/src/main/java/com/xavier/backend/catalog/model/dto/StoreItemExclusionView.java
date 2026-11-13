package com.xavier.backend.catalog.model.dto;

import java.time.Instant;
import java.util.UUID;

/** An item a store does not sell (D146), with the item's code and name. */
public record StoreItemExclusionView(UUID storeId, UUID itemId, String itemCode, String itemName, String reason,
                                     Instant excludedAt, UUID excludedByAccountId) {
}
