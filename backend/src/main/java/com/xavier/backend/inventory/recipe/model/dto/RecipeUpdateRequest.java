package com.xavier.backend.inventory.recipe.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RecipeUpdateRequest(@NotBlank @Size(max = 160) String name, @NotNull Long version) {
}
