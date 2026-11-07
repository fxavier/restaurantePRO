package com.xavier.backend.inventory.count.model.dto;

import com.xavier.backend.inventory.count.model.StockCount;
import com.xavier.backend.inventory.count.model.StockCountLine;
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

        static Line from(StockCountLine line) {
            return new Line(line.getId(), line.getStockItemId(), line.getCountedQuantity(), line.getUnit(),
                    line.getCountedAt(), line.getUnitCost(), line.getExpectedQuantity(), line.getDifference());
        }
    }

    public static CountView from(StockCount count) {
        return new CountView(count.getId(), count.getStoreId(), count.getWarehouseId(), count.getKind().name(),
                count.getScope().name(), count.getStatus().name(), count.getCreatedAt(), count.getStartedAt(),
                count.getSubmittedAt(), count.getDecidedAt(), count.getDecidedByAccountId(), count.getRejectReason(),
                count.getCancelledAt(), count.getLines().stream().map(Line::from).toList(), count.getVersion());
    }
}
