package com.xavier.backend.orders.model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** {@code quantity} of the source order's confirmed line {@code lineId}. */
public record TransferLineRequest(
        @NotNull UUID lineId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3) BigDecimal quantity) {
}
