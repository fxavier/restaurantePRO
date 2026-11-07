package com.xavier.backend.inventory.recipe.model;

import com.xavier.backend.inventory.recipe.exception.RecipeConflictException;
import com.xavier.backend.inventory.recipe.exception.RecipeRuleException;
import com.xavier.backend.inventory.recipe.model.dto.RecipeVersionView;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/**
 * A version of a recipe (D115): its yield and ingredients, edited only while DRAFT. Activating it makes it effective
 * from an instant; the next activation supersedes it at its own start, so the periods of a recipe never overlap and
 * consumption finds exactly one version for any confirmation instant. Illegal transitions are 409
 * {@code RECIPE_VERSION_IMMUTABLE}; the database refuses them too (trigger).
 */
@Entity
@Table(schema = "inventory", name = "recipe_version")
public class RecipeVersion {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID recipeId;

    @Column(nullable = false, updatable = false)
    private int versionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private VersionStatus status;

    @Column(nullable = false, precision = 19, scale = 6)
    private BigDecimal yieldQuantity;

    @Column(nullable = false, length = 10)
    private String yieldUnit;

    private Instant effectiveFrom;

    private Instant effectiveTo;

    private String notes;

    @Column(updatable = false)
    private UUID createdByAccountId;

    private UUID activatedByAccountId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant activatedAt;

    @OneToMany(mappedBy = "recipeVersion", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<RecipeIngredient> ingredients = new ArrayList<>();

    @Version
    private Long version;

    protected RecipeVersion() {
        // JPA
    }

    public static RecipeVersion draft(UUID recipeId, int versionNo, BigDecimal yieldQuantity, String yieldUnit,
                                      String notes, List<IngredientLine> ingredients, UUID accountId, Instant now) {
        RecipeVersion version = new RecipeVersion();
        version.id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
        version.recipeId = recipeId;
        version.versionNo = versionNo;
        version.status = VersionStatus.DRAFT;
        version.createdByAccountId = accountId;
        version.createdAt = now;
        version.write(yieldQuantity, yieldUnit, notes, ingredients);
        return version;
    }

    /** Replaces the yield, notes and ingredients of a DRAFT. */
    public void revise(BigDecimal yieldQuantity, String yieldUnit, String notes, List<IngredientLine> lines) {
        requireDraft("be edited");
        write(yieldQuantity, yieldUnit, notes, lines);
    }

    /** DRAFT → ACTIVE, effective from {@code from}. */
    public void activate(Instant from, UUID accountId, Instant now) {
        requireDraft("be activated");
        status = VersionStatus.ACTIVE;
        effectiveFrom = from;
        activatedByAccountId = accountId;
        activatedAt = now;
    }

    /**
     * ACTIVE → SUPERSEDED: it stops being effective at {@code to}, the next version's start.
     *
     * @throws RecipeRuleException RECIPE_EFFECTIVE_FROM_INVALID when {@code to} is not after its own start
     */
    public void supersedeAt(Instant to) {
        if (status != VersionStatus.ACTIVE) {
            throw RecipeConflictException.immutable(id, status, "be superseded");
        }
        if (!to.isAfter(effectiveFrom)) {
            throw RecipeRuleException.effectiveFromInvalid(effectiveFrom);
        }
        status = VersionStatus.SUPERSEDED;
        effectiveTo = to;
    }

    /** Whether it is the version in force at {@code at}: [effectiveFrom, effectiveTo), never for a DRAFT. */
    public boolean isEffectiveAt(Instant at) {
        return status != VersionStatus.DRAFT && !at.isBefore(effectiveFrom)
                && (effectiveTo == null || at.isBefore(effectiveTo));
    }

    public RecipeVersionView toView() {
        return new RecipeVersionView(id, recipeId, versionNo, status.name(), yieldQuantity, yieldUnit, effectiveFrom,
                effectiveTo, notes, activatedAt, getVersion(),
                ingredients.stream().map(RecipeIngredient::toView).toList());
    }

    private void write(BigDecimal yieldQuantity, String yieldUnit, String notes, List<IngredientLine> lines) {
        this.yieldQuantity = yieldQuantity;
        this.yieldUnit = yieldUnit;
        this.notes = notes;
        ingredients.clear();
        for (int i = 0; i < lines.size(); i++) {
            ingredients.add(RecipeIngredient.of(this, lines.get(i), i));
        }
    }

    private void requireDraft(String action) {
        if (status != VersionStatus.DRAFT) {
            throw RecipeConflictException.immutable(id, status, action);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getRecipeId() {
        return recipeId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public VersionStatus getStatus() {
        return status;
    }

    public BigDecimal getYieldQuantity() {
        return yieldQuantity;
    }

    public String getYieldUnit() {
        return yieldUnit;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }

    public Instant getEffectiveTo() {
        return effectiveTo;
    }

    public String getNotes() {
        return notes;
    }

    public UUID getActivatedByAccountId() {
        return activatedByAccountId;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public List<RecipeIngredient> getIngredients() {
        return Collections.unmodifiableList(ingredients);
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
