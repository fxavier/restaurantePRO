package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record ItemPriceUpdateRequest(@NotNull @PositiveOrZero BigDecimal amount, @NotNull Long version) {
}
