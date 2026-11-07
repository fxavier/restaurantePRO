package com.xavier.backend.inventory.model.dto;

import java.util.UUID;

public record WarehouseView(UUID id, UUID storeId, String code, String name, String negativePolicy, boolean active,
                            long version) {
}
