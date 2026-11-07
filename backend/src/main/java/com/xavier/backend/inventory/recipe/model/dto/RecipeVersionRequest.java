package com.xavier.backend.inventory.recipe.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * A version's yield and ingredients: each ingredient's quantity is for {@code yieldQuantity} of {@code yieldUnit}
 * (sale recipes yield sold units, so a unit of COUNT; preparation recipes yield their output item). {@code version}
 * is the draft's optimistic-lock version on PUT, ignored on POST.
 */
public record RecipeVersionRequest(@NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal yieldQuantity,
                                   @NotBlank @Size(max = 10) String yieldUnit, @Size(max = 2000) String notes,
                                   @NotEmpty @Size(max = 200) List<@NotNull @Valid IngredientRequest> ingredients,
                                   Long version) {
}
