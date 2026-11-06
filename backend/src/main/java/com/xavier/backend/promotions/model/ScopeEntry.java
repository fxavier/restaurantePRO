package com.xavier.backend.promotions.model;

import jakarta.persistence.Embeddable;
import java.util.UUID;

/** One row of a promotion's scope: exactly one of an item, a variant or a family. */
@Embeddable
public class ScopeEntry {

    private UUID itemId;
    private UUID variantId;
    private UUID familyId;

    protected ScopeEntry() {
        // JPA
    }

    private ScopeEntry(UUID itemId, UUID variantId, UUID familyId) {
        this.itemId = itemId;
        this.variantId = variantId;
        this.familyId = familyId;
    }

    static ScopeEntry item(UUID id) {
        return new ScopeEntry(id, null, null);
    }

    static ScopeEntry variant(UUID id) {
        return new ScopeEntry(null, id, null);
    }

    static ScopeEntry family(UUID id) {
        return new ScopeEntry(null, null, id);
    }

    public UUID getItemId() {
        return itemId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public UUID getFamilyId() {
        return familyId;
    }
}
