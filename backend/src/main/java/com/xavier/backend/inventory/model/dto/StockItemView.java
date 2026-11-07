package com.xavier.backend.inventory.model.dto;

import java.util.UUID;

public record StockItemView(UUID id, String code, String name, String baseUnit, String kind, boolean active,
                            long version) {
}
