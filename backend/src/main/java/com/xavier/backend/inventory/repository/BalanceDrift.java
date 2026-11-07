package com.xavier.backend.inventory.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** A balance row that does not equal the sum of its movements (D113), or movements with no balance row. */
public record BalanceDrift(UUID storeId, UUID warehouseId, UUID stockItemId, BigDecimal balanceQuantity,
                           BigDecimal balanceValue, BigDecimal ledgerQuantity, BigDecimal ledgerValue) {
}
