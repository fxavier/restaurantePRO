package com.xavier.backend.catalog.model.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** An item, or one of its variants ({@code variantId}), and a quantity: a menu option or a macro entry. */
public record ItemLineRequest(@NotNull UUID itemId, UUID variantId,
                              @NotNull @Digits(integer = 9, fraction = 3) BigDecimal quantity) {
}
