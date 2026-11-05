package com.xavier.backend.settlement.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** The blind count of one payment method at session close, in the store currency. */
public record CountLine(@NotBlank @Size(max = 30) String methodCode, @NotNull BigDecimal amount) {
}
