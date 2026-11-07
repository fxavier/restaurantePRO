package com.xavier.backend.inventory.purchasing.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code lastUnitCost} is per base unit (scale 6), {@code null} until known. */
public record SupplierItemView(UUID id, UUID supplierId, UUID stockItemId, UUID packId, String supplierCode,
                               BigDecimal lastUnitCost, Instant lastCostAt) {
}
