package com.xavier.backend.customers.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** A new credit limit, in the company currency, and why (spec §9). */
public record CreditLimitChangeRequest(@NotNull @PositiveOrZero BigDecimal newLimit,
                                       @NotBlank @Size(max = 500) String reason) {
}
