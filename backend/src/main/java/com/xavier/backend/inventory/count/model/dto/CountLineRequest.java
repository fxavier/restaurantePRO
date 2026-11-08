package com.xavier.backend.inventory.count.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * What was counted of one stock item, in {@code unit} (the item's base unit when null), at {@code countedAt} (now
 * when null): the line's cut-off. {@code unitCost} (per base unit) on an opening count only.
 */
public record CountLineRequest(@NotNull @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal countedQuantity,
                               String unit, Instant countedAt,
                               @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal unitCost) {
}
