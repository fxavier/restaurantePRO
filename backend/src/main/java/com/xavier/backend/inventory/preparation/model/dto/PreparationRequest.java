package com.xavier.backend.inventory.preparation.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** {@code outputQuantity} of {@code unit} (the prepared item's base unit when absent), made in {@code warehouseId}. */
public record PreparationRequest(@NotNull UUID warehouseId, @NotNull UUID outputStockItemId,
                                 @NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal outputQuantity,
                                 @Size(max = 10) String unit, @Size(max = 2000) String notes) {
}
