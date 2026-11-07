package com.xavier.backend.inventory.recipe.model;

import com.xavier.backend.inventory.recipe.model.dto.IngredientView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** An ingredient line of a recipe version (D115); immutable once its version leaves DRAFT (trigger). */
@Entity
@Table(schema = "inventory", name = "recipe_ingredient")
public class RecipeIngredient {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "recipe_version_id", nullable = false, updatable = false)
    private RecipeVersion recipeVersion;

    @Column(nullable = false, updatable = false)
    private UUID stockItemId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal quantity;

    @Column(nullable = false, updatable = false, length = 10)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 10)
    private IngredientKind kind;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(updatable = false, columnDefinition = "text[]")
    private String[] modes;

    @Column(nullable = false, updatable = false)
    private int position;

    protected RecipeIngredient() {
        // JPA
    }

    static RecipeIngredient of(RecipeVersion version, IngredientLine line, int position) {
        RecipeIngredient ingredient = new RecipeIngredient();
        ingredient.recipeVersion = version;
        ingredient.stockItemId = line.stockItemId();
        ingredient.quantity = line.quantity();
        ingredient.unit = line.unit();
        ingredient.kind = line.kind();
        ingredient.modes = line.modes() == null || line.modes().isEmpty() ? null : line.modes().toArray(String[]::new);
        ingredient.position = position;
        return ingredient;
    }

    /** Whether it is consumed by an order of {@code mode} (packaging limited to modes, D115). */
    public boolean appliesTo(String mode) {
        return modes == null || List.of(modes).contains(mode);
    }

    public IngredientLine toLine() {
        return new IngredientLine(stockItemId, quantity, unit, kind, modes == null ? null : List.of(modes));
    }

    public IngredientView toView() {
        return new IngredientView(stockItemId, quantity, unit, kind.name(), modes == null ? null : List.of(modes));
    }

    public UUID getStockItemId() {
        return stockItemId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getUnit() {
        return unit;
    }

    public IngredientKind getKind() {
        return kind;
    }
}
