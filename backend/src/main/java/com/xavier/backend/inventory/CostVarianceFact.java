package com.xavier.backend.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A cost variance (D125 as amended), as reporting reads it (D131): cost of goods already consumed that a receipt cost
 * correction or a linked supplier return changed. {@code value} (scale 4) is signed: positive is more cost of goods,
 * negative less. {@code sourceType} is {@code COST_CORRECTION} (its id the correction's) or {@code SUPPLIER_RETURN}
 * (the return's), the sources {@code StockMovementsPosted} names; {@code receiptLineId} is the receipt line it is of,
 * {@code businessDate} the store's business day when it was recorded.
 */
public record CostVarianceFact(UUID varianceId, String sourceType, UUID sourceId, UUID storeId, UUID warehouseId,
                               UUID stockItemId, UUID receiptLineId, BigDecimal value, String currency,
                               LocalDate businessDate) {
}
