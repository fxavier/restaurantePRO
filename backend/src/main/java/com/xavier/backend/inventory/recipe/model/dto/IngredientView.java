package com.xavier.backend.inventory.recipe.model.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record IngredientView(UUID stockItemId, BigDecimal quantity, String unit, String kind, List<String> modes) {
}
