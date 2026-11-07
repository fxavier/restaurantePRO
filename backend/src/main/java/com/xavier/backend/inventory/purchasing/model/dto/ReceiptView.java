package com.xavier.backend.inventory.purchasing.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A goods receipt. {@code warnings} holds {@code OVER_RECEIPT} when, once confirmed, more of an order line arrived
 * than was ordered (allowed and reported, D125).
 */
public record ReceiptView(UUID id, UUID storeId, UUID warehouseId, UUID supplierId, UUID purchaseOrderId,
                          String supplierDocumentRef, String status, Instant receivedAt, Instant createdAt,
                          Instant confirmedAt, LocalDate businessDate, Instant cancelledAt, List<Line> lines,
                          List<String> warnings, long version) {

    /** {@code unitCostBase} as received; {@code currentUnitCost} after the cost corrections, per base unit. */
    public record Line(UUID id, UUID purchaseOrderLineId, UUID stockItemId, UUID packId, BigDecimal packQuantity,
                       BigDecimal quantityBase, BigDecimal unitCostBase, BigDecimal currentUnitCost) {
    }
}
