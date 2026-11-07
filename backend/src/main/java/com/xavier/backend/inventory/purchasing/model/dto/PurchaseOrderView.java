package com.xavier.backend.inventory.purchasing.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A purchase order with its receipt progress, derived from confirmed receipts (D124): {@code NONE}, {@code PARTIAL}
 * or {@code FULL}, per line and for the order.
 */
public record PurchaseOrderView(UUID id, UUID storeId, UUID warehouseId, UUID supplierId, String number,
                                String status, String progress, LocalDate expectedAt, String notes,
                                Instant createdAt, Instant submittedAt, Instant closedAt, Instant cancelledAt,
                                List<Line> lines, long version) {

    /**
     * {@code quantity} in the purchase unit; {@code ordered}, {@code received} and {@code outstanding} in the base
     * unit; {@code unitCost} per base unit.
     */
    public record Line(UUID id, UUID stockItemId, UUID packId, BigDecimal quantity, BigDecimal ordered,
                       BigDecimal unitCost, BigDecimal received, BigDecimal outstanding, String progress) {
    }
}
