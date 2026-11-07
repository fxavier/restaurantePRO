package com.xavier.backend.inventory.purchasing.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * What arrived of one stock item: {@code packQuantity} packs of {@code packId} (or base units without a pack), at
 * {@code unitCost} per pack (or base unit) as invoiced. Without a cost it defaults to the order line's, else the
 * supplier's last cost.
 */
public record ReceiptLineRequest(
        UUID purchaseOrderLineId,
        @NotNull UUID stockItemId,
        UUID packId,
        @NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal packQuantity,
        @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal unitCost) {
}
