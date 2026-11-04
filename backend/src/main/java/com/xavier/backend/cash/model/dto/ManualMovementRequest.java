package com.xavier.backend.cash.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** A cash in, cash out or authorized expense in the operator's own OPEN session; always in cash. */
public record ManualMovementRequest(
        @NotNull UUID sessionId,
        @NotNull ManualMovementType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(max = 500) String reason) {
}
