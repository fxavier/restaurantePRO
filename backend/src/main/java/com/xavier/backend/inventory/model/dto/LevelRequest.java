package com.xavier.backend.inventory.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/** The minimum of a stock item in a warehouse, in the item's base unit. */
public record LevelRequest(
        @NotNull UUID warehouseId,
        @NotNull UUID stockItemId,
        @NotNull @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal minQuantity) {
}
