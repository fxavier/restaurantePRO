package com.xavier.backend.inventory.purchasing.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * The corrected cost of a confirmed receipt line, per base unit (D125). {@code reason} is checked by the use case
 * (422 REASON_REQUIRED).
 */
public record CostCorrectionRequest(
        @NotNull @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal newUnitCost,
        @Size(max = 500) String reason) {
}
