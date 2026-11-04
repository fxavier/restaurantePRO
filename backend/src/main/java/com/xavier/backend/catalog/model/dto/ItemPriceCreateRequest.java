package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;

/** {@code amount} is in the list currency, with at most its minor-unit decimals (2 for MZN). */
public record ItemPriceCreateRequest(@NotNull UUID itemId, UUID variantId, @NotNull @PositiveOrZero BigDecimal amount) {
}
