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

    Optional<RecipeVersion> findByRecipeIdAndPeriodStatus(UUID recipeId, VersionStatus status);

    @Query("SELECT coalesce(max(v.versionNo), 0) FROM RecipeVersion v WHERE v.recipeId = :recipeId")
    int maxVersionNo(UUID recipeId);

    /** The version in force at {@code at}: [effectiveFrom, effectiveTo) holds it (at most one, D115). */
    @Query("""
            SELECT v FROM RecipeVersion v
            WHERE v.recipeId = :recipeId
              AND v.period.status <> com.xavier.backend.inventory.recipe.model.VersionStatus.DRAFT
              AND v.period.effectiveFrom <= :at AND (v.period.effectiveTo IS NULL OR v.period.effectiveTo > :at)""")
    Optional<RecipeVersion> effectiveAt(UUID recipeId, Instant at);

    /** The versions that took effect after {@code at}, the first first (a late recipe, for reprocessing). */
    @Query("""
            SELECT v FROM RecipeVersion v
            WHERE v.recipeId = :recipeId
              AND v.period.status <> com.xavier.backend.inventory.recipe.model.VersionStatus.DRAFT
              AND v.period.effectiveFrom > :at
            ORDER BY v.period.effectiveFrom""")
    List<RecipeVersion> effectiveAfter(UUID recipeId, Instant at);
}
