package com.xavier.backend.inventory.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferView(UUID id, UUID fromWarehouseId, UUID toWarehouseId, UUID fromStoreId, UUID toStoreId,
                           String status, String notes, Instant createdAt, Instant shippedAt, Instant receivedAt,
                           List<Line> lines, long version) {

    /** {@code shortfall} is null until received. */
    public record Line(UUID stockItemId, BigDecimal quantityShipped, BigDecimal quantityReceived, BigDecimal unitCost,
                       BigDecimal shortfall) {
    }
}
