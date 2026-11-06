package com.xavier.backend.orders.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Full replacement of a DRAFT line's variant, quantity and notes; {@code version} is the line's (D17). */
public record UpdateLineRequest(
        @NotNull Long version,
        UUID variantId,
        @NotNull @Positive @Digits(integer = 9, fraction = 3) BigDecimal quantity,
        @Size(max = 200) String notes) {
}
