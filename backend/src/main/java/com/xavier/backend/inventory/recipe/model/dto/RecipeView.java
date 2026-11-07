package com.xavier.backend.inventory.recipe.model.dto;

import java.util.List;
import java.util.UUID;

/** A recipe and its versions in version order ({@code versions} is empty in lists). */
public record RecipeView(UUID id, UUID itemId, UUID variantId, UUID outputStockItemId, String name, long version,
                         List<RecipeVersionView> versions) {
}
