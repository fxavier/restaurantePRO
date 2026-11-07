package com.xavier.backend.inventory.purchasing.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** What arrived of one purchase order line, in the base unit. */
public record Received(UUID purchaseOrderLineId, BigDecimal quantityBase) {
}
