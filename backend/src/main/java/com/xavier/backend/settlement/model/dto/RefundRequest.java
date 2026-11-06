package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * {@code POST /api/v1/pos/payments/{id}/refunds} (D77): how much of the payment goes back, why, and, for a method
 * other than cash, the reference of the refund made on the terminal or the provider's portal.
 */
public record RefundRequest(@NotNull BigDecimal amount, @NotBlank @Size(max = 500) String reason,
                            @Size(max = 100) String externalReference) {
}
