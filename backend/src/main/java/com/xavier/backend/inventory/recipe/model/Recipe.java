package com.xavier.backend.inventory.recipe.model;

import com.xavier.backend.inventory.recipe.model.dto.RecipeVersionView;
import com.xavier.backend.inventory.recipe.model.dto.RecipeView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A recipe (D115): a sale recipe of a catalog item (or one of its variants, whose recipe wins over the item's), or a
 * preparation recipe of a PREPARED stock item. Its content lives in its {@link RecipeVersion}s; only the name is
 * edited here.
 */
@Entity
@Table(schema = "inventory", name = "recipe")
public class Recipe {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(updatable = false)
    private UUID itemId;

    @Column(updatable = false)
    private UUID variantId;

    @Column(updatable = false)
    private UUID outputStockItemId;

    @Column(nullable = false, length = 160)
    private String name;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected Recipe() {
        // JPA
    }

    public static Recipe forSale(UUID itemId, UUID variantId, String name, Instant now) {
        Recipe recipe = new Recipe();
        recipe.itemId = itemId;
        recipe.variantId = variantId;
        recipe.name = name.strip();
        recipe.createdAt = now;
        return recipe;
    }

    public static Recipe forPreparation(UUID outputStockItemId, String name, Instant now) {
        Recipe recipe = new Recipe();
        recipe.outputStockItemId = outputStockItemId;
        recipe.name = name.strip();
        recipe.createdAt = now;
        return recipe;
    }

    public void rename(String name) {
        this.name = name.strip();
    }

    public RecipeView toView(List<RecipeVersionView> versions) {
        return new RecipeView(id, itemId, variantId, outputStockItemId, name, getVersion(), versions);
    }

    public boolean isPreparation() {
        return outputStockItemId != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getItemId() {
        return itemId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public UUID getOutputStockItemId() {
        return outputStockItemId;
    }

    public String getName() {
        return name;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
