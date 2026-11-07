package com.xavier.backend.inventory.recipe.model;

import com.xavier.backend.inventory.recipe.exception.RecipeConflictException;
import com.xavier.backend.inventory.recipe.exception.RecipeRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.UUID;

/**
 * The life of a versioned rule (a recipe version, D115; a consumption policy version, D116): DRAFT, then ACTIVE from
 * an instant, then SUPERSEDED at the next version's start. It is in force over {@code [effectiveFrom, effectiveTo)},
 * so consumption finds exactly one version for any confirmation instant. An illegal transition is 409
 * {@code RECIPE_VERSION_IMMUTABLE}.
 */
@Embeddable
public class EffectivePeriod {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private VersionStatus status;

    private Instant effectiveFrom;

    private Instant effectiveTo;

    protected EffectivePeriod() {
        // JPA
    }

    public static EffectivePeriod draft() {
        EffectivePeriod period = new EffectivePeriod();
        period.status = VersionStatus.DRAFT;
        return period;
    }

    public void requireDraft(UUID versionId, String action) {
        if (status != VersionStatus.DRAFT) {
            throw RecipeConflictException.immutable(versionId, status, action);
        }
    }

    public void activate(UUID versionId, Instant from) {
        requireDraft(versionId, "be activated");
        status = VersionStatus.ACTIVE;
        effectiveFrom = from;
    }

    /** @throws RecipeRuleException RECIPE_EFFECTIVE_FROM_INVALID when {@code to} is not after its own start */
    public void supersedeAt(UUID versionId, Instant to) {
        if (status != VersionStatus.ACTIVE) {
            throw RecipeConflictException.immutable(versionId, status, "be superseded");
        }
        if (!to.isAfter(effectiveFrom)) {
            throw RecipeRuleException.effectiveFromInvalid(effectiveFrom);
        }
        status = VersionStatus.SUPERSEDED;
        effectiveTo = to;
    }

    public boolean isEffectiveAt(Instant at) {
        return status != VersionStatus.DRAFT && !at.isBefore(effectiveFrom)
                && (effectiveTo == null || at.isBefore(effectiveTo));
    }

    public VersionStatus status() {
        return status;
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public Instant effectiveTo() {
        return effectiveTo;
    }
}
