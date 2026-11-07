package com.xavier.backend.inventory.recipe.model.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RecipeVersionView(UUID id, UUID recipeId, int versionNo, String status, BigDecimal yieldQuantity,
                                String yieldUnit, Instant effectiveFrom, Instant effectiveTo, String notes,
                                Instant activatedAt, long version, List<IngredientView> ingredients) {
}
