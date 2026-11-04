package com.xavier.backend.dining.model.dto;

import java.util.UUID;

/** {@code locked} is set from the POS (TABLE_LOCK) and also changes {@code version}. */
public record RestaurantTableSummary(UUID id, UUID storeId, UUID areaId, String code, int seats, boolean active,
                                     boolean locked, long version) {
}
