package com.xavier.backend.orders.delivery.model.dto;

import java.util.UUID;

public record ZoneView(UUID id, UUID storeId, String code, String name, UUID feeItemId, UUID feeVariantId,
                       boolean active, long version) {
}
