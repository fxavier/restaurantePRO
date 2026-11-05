package com.xavier.backend.orders.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** A new DRAFT line; {@code quantity} is decimal for items sold by weight (NUMERIC(12,3)). */
public record AddLineRequest(
        @NotNull UUID itemId,
        UUID variantId,
        @NotNull @Positive @Digits(integer = 9, fraction = 3) BigDecimal quantity,
        @Size(max = 200) String notes) {
}
