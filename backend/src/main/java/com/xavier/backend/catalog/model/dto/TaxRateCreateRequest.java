package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

/** A new rate for a tax category from {@code effectiveFrom} on; {@code ratePercent} 16 means 16%. */
public record TaxRateCreateRequest(
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal ratePercent,
        @NotNull Instant effectiveFrom) {
}
