package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /api/v1/pos/customers/{id}/credit-payments} (D75): a debt payment of at most the customer's open debt
 * (P33). {@code tendered} is for cash only. {@code debitEntryIds} names the debits it settles, in that order; without
 * them the oldest open debits are settled first (FIFO).
 */
public record CreditPaymentRequest(@NotBlank @Size(max = 30) String methodCode, @NotNull @Positive BigDecimal amount,
                                   @Positive BigDecimal tendered, @Size(max = 100) List<@NotNull UUID> debitEntryIds) {
}
