package com.xavier.backend.inventory.purchasing.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** One stock item sent back: {@code quantity} of {@code unit} (the base unit when null), from a receipt line or not. */
public record ReturnLineRequest(
        UUID receiptLineId,
        @NotNull UUID stockItemId,
        @NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal quantity,
        @Size(max = 10) String unit) {
}
