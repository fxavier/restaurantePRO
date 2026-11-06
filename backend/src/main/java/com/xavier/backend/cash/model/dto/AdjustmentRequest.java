package com.xavier.backend.cash.model.dto;

import com.xavier.backend.cash.model.MovementDirection;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** A later correction of a CLOSED session (D79 (1)): money in or out of a method, with the reason. */
public record AdjustmentRequest(
        @NotNull MovementDirection direction,
        @NotBlank @Size(max = 30) String methodCode,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotBlank @Size(max = 500) String reason) {
}
