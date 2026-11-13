package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.model.dto.StoreItemExclusionView;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * An item that a store does not sell (D146, "disponibilidade"): configuration of the working copy, effective at the
 * store's next publication. No row = the item is in the store. Rows are written race-free by the repository
 * ({@code ON CONFLICT DO NOTHING}), so the entity itself is read-only.
 */
@Entity
@Immutable
@Table(schema = "catalog", name = "store_item_exclusion")
public class StoreItemExclusion {

    @Embeddable
    public record Key(UUID storeId, UUID itemId) {
    }

    @EmbeddedId
    private Key id;

    private String reason;

    @Column(nullable = false)
    private Instant excludedAt;

    @Column(nullable = false)
    private UUID excludedByAccountId;

    protected StoreItemExclusion() {
        // JPA
    }

    public UUID getItemId() {
        return id.itemId();
    }

    public String getReason() {
        return reason;
    }

    /** {@code code} and {@code name} of the item, read by the caller. */
    public StoreItemExclusionView toView(String code, String name) {
        return new StoreItemExclusionView(id.storeId(), id.itemId(), code, name, reason, excludedAt,
                excludedByAccountId);
    }
}
