package com.xavier.backend.settlement.model.dto;

import com.xavier.backend.settlement.model.PaymentMethodKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/backoffice/payment-methods}. {@code code} is upper case ({@code [A-Z0-9_]}, at most 30) and never
 * changes; {@code providerCode} names the {@code PaymentProvider} of an {@code INTEGRATED} method (and only of one).
 */
public record PaymentMethodCreateRequest(@NotBlank @Pattern(regexp = "[A-Z0-9_]{1,30}") String code,
                                         @NotBlank @Size(max = 100) String name, @NotNull PaymentMethodKind kind,
                                         @Size(max = 50) String providerCode) {
}
