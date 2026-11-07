package com.xavier.backend.inventory.recipe.repository;

import com.xavier.backend.inventory.recipe.model.RecipeVersion;
import com.xavier.backend.inventory.recipe.model.VersionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RecipeVersionRepository extends JpaRepository<RecipeVersion, UUID> {

    List<RecipeVersion> findByRecipeIdOrderByVersionNo(UUID recipeId);

    Optional<RecipeVersion> findByRecipeIdAndVersionNo(UUID recipeId, int versionNo);

    Optional<RecipeVersion> findByRecipeIdAndStatus(UUID recipeId, VersionStatus status);

    @Query("SELECT coalesce(max(v.versionNo), 0) FROM RecipeVersion v WHERE v.recipeId = :recipeId")
    int maxVersionNo(UUID recipeId);

    /** The version in force at {@code at}: [effectiveFrom, effectiveTo) holds it (at most one, D115). */
    @Query("""
            SELECT v FROM RecipeVersion v
            WHERE v.recipeId = :recipeId AND v.status <> com.xavier.backend.inventory.recipe.model.VersionStatus.DRAFT
              AND v.effectiveFrom <= :at AND (v.effectiveTo IS NULL OR v.effectiveTo > :at)""")
    Optional<RecipeVersion> effectiveAt(UUID recipeId, Instant at);

    /** The first version that took effect after {@code at} (a late recipe, for reprocessing). */
    Optional<RecipeVersion> findFirstByRecipeIdAndStatusNotAndEffectiveFromAfterOrderByEffectiveFromAsc(
            UUID recipeId, VersionStatus status, Instant at);
}
