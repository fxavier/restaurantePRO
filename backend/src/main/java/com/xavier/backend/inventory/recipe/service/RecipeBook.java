package com.xavier.backend.inventory.recipe.service;

import com.xavier.backend.inventory.recipe.model.Recipe;
import com.xavier.backend.inventory.recipe.model.RecipeVersion;
import com.xavier.backend.inventory.recipe.model.VersionStatus;
import com.xavier.backend.inventory.recipe.repository.RecipeRepository;
import com.xavier.backend.inventory.recipe.repository.RecipeVersionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which recipe version applies (D115), for consumption (S47) and preparations (D121), in the caller's transaction.
 * A variant's recipe wins over its item's; when the variant's recipe has no version in force, the item's applies.
 */
@Service
public class RecipeBook {

    private final RecipeRepository recipes;
    private final RecipeVersionRepository versions;

    RecipeBook(RecipeRepository recipes, RecipeVersionRepository versions) {
        this.recipes = recipes;
        this.versions = versions;
    }

    /** The sale recipe version in force at {@code at} (a line's confirmation instant). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RecipeVersion> saleVersionAt(UUID itemId, UUID variantId, Instant at) {
        return saleVersion(itemId, variantId, recipe -> versions.effectiveAt(recipe.getId(), at));
    }

    /** The first sale recipe version that took effect after {@code at}: a late recipe, for a reprocessed line. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RecipeVersion> firstSaleVersionAfter(UUID itemId, UUID variantId, Instant at) {
        return saleVersion(itemId, variantId,
                recipe -> versions.effectiveAfter(recipe.getId(), at).stream().findFirst());
    }

    /** The ACTIVE version of the preparation recipe of {@code outputStockItemId}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<RecipeVersion> activePreparation(UUID outputStockItemId) {
        return recipes.findByOutputStockItemId(outputStockItemId)
                .flatMap(recipe -> versions.findByRecipeIdAndPeriodStatus(recipe.getId(), VersionStatus.ACTIVE));
    }

    private Optional<RecipeVersion> saleVersion(UUID itemId, UUID variantId,
                                                Function<Recipe, Optional<RecipeVersion>> version) {
        Optional<RecipeVersion> ofVariant = variantId == null ? Optional.empty()
                : recipes.findByItemIdAndVariantId(itemId, variantId).flatMap(version);
        return ofVariant.or(() -> recipes.findByItemIdAndVariantIdIsNull(itemId).flatMap(version));
    }
}
