package com.xavier.backend.dining.model.dto;

import java.util.UUID;

public record RestaurantTableSummary(UUID id, UUID storeId, UUID areaId, String code, int seats, boolean active,
                                     long version) {
}
