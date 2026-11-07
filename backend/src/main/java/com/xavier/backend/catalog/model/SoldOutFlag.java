package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.ItemKey;
import com.xavier.backend.catalog.model.dto.ItemAvailabilityView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * An item (variant {@code null}: with all its variants) or a variant that is sold out in a store (D109). Operational,
 * outside the release. Rows are inserted race-free with {@code ON CONFLICT DO NOTHING} and removed when the item is
 * available again, so the entity itself is read-only.
 */
@Entity
@Immutable
@Table(schema = "catalog", name = "item_availability")
public class SoldOutFlag {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID storeId;

    @Column(nullable = false)
    private UUID itemId;

    private UUID variantId;

    @Column(nullable = false)
    private Instant soldOutAt;

    @Column(name = "sold_out_by_actor_kind", nullable = false)
    private String soldOutByActorKind;

    @Column(name = "sold_out_by_actor_id", nullable = false)
    private UUID soldOutByActorId;

    private String reason;

    protected SoldOutFlag() {
        // JPA
    }

    public ItemKey key() {
        return new ItemKey(itemId, variantId);
    }

    public ItemAvailabilityView toView() {
        return new ItemAvailabilityView(itemId, variantId, true, soldOutAt, reason);
    }

    public UUID getId() {
        return id;
    }
}
