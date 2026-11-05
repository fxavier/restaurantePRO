package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code PUT /api/v1/backoffice/payment-methods/{id}} at {@code version} (stale → 409). Code and kind stay. */
public record PaymentMethodUpdateRequest(@NotBlank @Size(max = 100) String name, @Size(max = 50) String providerCode,
                                         @NotNull Boolean active, @NotNull Long version) {
}
