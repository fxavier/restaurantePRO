package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code POST /api/v1/pos/payment-attempts}. {@code amount} is in the order's currency and at most the balance;
 * {@code tendered} is for cash only (change = tendered − amount). Store, terminal and session come from the POS
 * session (D39, P16). With {@code partitionId} the attempt pays that partition of the order and the amount is at
 * most its balance; without it, the order's residual (D69).
 */
public record CreateAttemptRequest(@NotNull UUID orderId, UUID partitionId, @NotBlank @Size(max = 30) String methodCode,
                                   @NotNull @Positive BigDecimal amount, @Positive BigDecimal tendered) {
}
