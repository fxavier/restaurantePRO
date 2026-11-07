package com.xavier.backend.inventory.model.dto;

import java.util.UUID;

public record RouteView(UUID id, UUID storeId, String destinationCode, UUID warehouseId, long version) {
}
