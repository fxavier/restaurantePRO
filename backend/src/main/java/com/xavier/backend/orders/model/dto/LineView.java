package com.xavier.backend.orders.model.dto;

import com.xavier.backend.orders.model.LineStatus;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A line as the POS shows it. A DRAFT carries a preview ({@code preview = true}): the price the current catalog would
 * give it, {@code null} when the item left the catalog, and no snapshot. A CONFIRMED line carries its snapshot, its
 * net quantity and its gross for that net quantity (D59).
 */
public record LineView(UUID id, LineStatus status, UUID itemId, UUID variantId, BigDecimal quantity,
                       BigDecimal netQuantity, String notes, long version, UUID batchId, String itemCode,
                       String description, String variantName, Money unitPrice, BigDecimal taxRatePercent,
                       Money gross, boolean preview) {
}
