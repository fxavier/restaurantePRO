package com.xavier.backend.inventory.recipe.model.dto;

import com.xavier.backend.inventory.recipe.model.IngredientKind;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** {@code modes} limits the line to those order modes (packaging for TAKEAWAY…); absent or empty = every mode. */
public record IngredientRequest(@NotNull UUID stockItemId,
                                @NotNull @Positive @Digits(integer = 13, fraction = 6) BigDecimal quantity,
                                @NotBlank @Size(max = 10) String unit, @NotNull IngredientKind kind,
                                @Size(max = 10) List<@NotNull @Pattern(regexp = "[A-Z_]{1,20}") String> modes) {
}
