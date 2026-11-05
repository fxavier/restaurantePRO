package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code POST /api/v1/pos/orders/{id}/credit-charges} (D74): puts {@code amount} of the order's residual, or of its
 * ITEMS partition {@code partitionId}, on the account of {@code customerId}, within its available credit (P29). The
 * amount is at most the target's balance. Store, terminal and employee come from the POS session.
 */
public record CreditChargeRequest(@NotNull UUID customerId, @NotNull @Positive BigDecimal amount, UUID partitionId) {
}
