package com.xavier.backend.inventory.recipe.repository;

import com.xavier.backend.inventory.recipe.model.Recipe;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RecipeRepository extends JpaRepository<Recipe, UUID> {

    Page<Recipe> findAllByOrderByNameAscIdAsc(Pageable pageable);

    Optional<Recipe> findByItemIdAndVariantId(UUID itemId, UUID variantId);

    Optional<Recipe> findByItemIdAndVariantIdIsNull(UUID itemId);

    Optional<Recipe> findByOutputStockItemId(UUID outputStockItemId);

    /** The recipe row {@code FOR UPDATE}: version numbering and activation of one recipe serialize on it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Recipe r WHERE r.id = :id")
    Optional<Recipe> lockById(UUID id);
}
