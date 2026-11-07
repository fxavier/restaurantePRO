package com.xavier.backend.inventory.purchasing.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record SupplierReturnView(UUID id, UUID storeId, UUID warehouseId, UUID supplierId, UUID goodsReceiptId,
                                 String reason, String status, Instant createdAt, Instant confirmedAt,
                                 LocalDate businessDate, Instant cancelledAt, List<Line> lines, long version) {

    public record Line(UUID id, UUID receiptLineId, UUID stockItemId, BigDecimal quantityBase) {
    }
}
