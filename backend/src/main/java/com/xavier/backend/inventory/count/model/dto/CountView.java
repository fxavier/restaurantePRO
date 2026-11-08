package com.xavier.backend.inventory.count.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A count with its lines; quantities in each item's base unit. {@code expected}/{@code difference} once approved. */
public record CountView(UUID id, UUID storeId, UUID warehouseId, String kind, String scope, String status,
                        Instant createdAt, Instant startedAt, Instant submittedAt, Instant decidedAt,
                        UUID decidedByAccountId, String rejectReason, Instant cancelledAt, List<Line> lines,
                        long version) {

    public record Line(UUID id, UUID stockItemId, BigDecimal countedQuantity, String unit, Instant countedAt,
                       BigDecimal unitCost, BigDecimal expectedQuantity, BigDecimal difference) {
    }
}
