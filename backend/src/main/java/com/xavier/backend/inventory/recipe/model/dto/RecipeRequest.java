package com.xavier.backend.inventory.recipe.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** A sale recipe ({@code itemId}, optionally {@code variantId}) or a preparation recipe ({@code outputStockItemId}). */
public record RecipeRequest(UUID itemId, UUID variantId, UUID outputStockItemId,
                            @NotBlank @Size(max = 160) String name) {
}
