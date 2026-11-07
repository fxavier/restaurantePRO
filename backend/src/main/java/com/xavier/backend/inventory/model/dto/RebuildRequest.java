package com.xavier.backend.inventory.model.dto;

import java.util.UUID;

/** Rebuild the balances of one warehouse, or of every warehouse when {@code warehouseId} is null. */
public record RebuildRequest(UUID warehouseId) {
}
