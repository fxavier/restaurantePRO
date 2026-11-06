package com.xavier.backend.inventory.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** {@code quantity} of {@code unit} (the item's base unit when null) to ship. */
public record TransferLineRequest(
        @NotNull UUID stockItemId,
        @NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal quantity,
        @Size(max = 10) String unit) {
}
