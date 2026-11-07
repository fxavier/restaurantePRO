package com.xavier.backend.inventory.purchasing.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One stock item of a purchase order: {@code quantity} packs of {@code packId}, or of the base unit without a pack.
 * {@code unitCost} is the price of one pack (or base unit); when omitted it defaults to the supplier's last cost.
 */
public record PurchaseLineRequest(
        @NotNull UUID stockItemId,
        UUID packId,
        @NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal quantity,
        @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal unitCost) {
}
