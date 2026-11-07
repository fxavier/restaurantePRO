package com.xavier.backend.inventory.recipe.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One ingredient of a recipe version as written: {@code quantity} of {@code unit} per yield; {@code modes} limits it to
 * those order modes ({@code null} = every mode).
 */
public record IngredientLine(UUID stockItemId, BigDecimal quantity, String unit, IngredientKind kind,
                             List<String> modes) {
}
