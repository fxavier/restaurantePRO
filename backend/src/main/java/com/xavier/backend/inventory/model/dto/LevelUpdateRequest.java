package com.xavier.backend.inventory.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record LevelUpdateRequest(
        @NotNull @PositiveOrZero @Digits(integer = 13, fraction = 6) BigDecimal minQuantity,
        @NotNull Long version) {
}
