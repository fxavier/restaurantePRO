package com.xavier.backend.inventory.model.dto;

import com.xavier.backend.inventory.model.StockItemKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A new stock item; {@code baseUnit} is a stock unit code (g, kg, ml, l, un) and never changes afterwards. */
public record StockItemRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 10) String baseUnit,
        @NotNull StockItemKind kind) {
}
