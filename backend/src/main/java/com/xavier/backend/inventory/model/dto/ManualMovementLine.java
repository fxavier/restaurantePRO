package com.xavier.backend.inventory.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One stock item of a manual movement: {@code quantity} of {@code unit} (the item's base unit when null); waste and
 * returns give a positive quantity, an adjustment a signed one. {@code unitCost} is per base unit, and only an
 * incoming adjustment uses it (it is required there).
 */
public record ManualMovementLine(
        @NotNull UUID stockItemId,
        @NotNull @Digits(integer = 13, fraction = 6) BigDecimal quantity,
        @Size(max = 10) String unit,
        @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal unitCost) {
}
