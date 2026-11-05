package com.xavier.backend.orders.model.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Voids {@code quantity} of a confirmed line (spec §4). {@code orderVersion} is the order the POS shows (stale → 409);
 * {@code wasted} says the goods are lost (inventory input, R4). The approver travels in the {@code X-Approver-Code}
 * and {@code X-Approver-Pin} headers, never in this body (D40).
 */
public record VoidLineRequest(
        @NotNull Long orderVersion,
        @NotNull UUID lineId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 9, fraction = 3) BigDecimal quantity,
        @NotBlank @Size(max = 200) String reason,
        @NotNull Boolean wasted) {
}
