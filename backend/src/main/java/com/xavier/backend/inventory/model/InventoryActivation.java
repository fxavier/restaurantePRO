package com.xavier.backend.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Go-live of inventory in a store (D122), a fact: from {@code activeFrom} on, confirmed lines are consumed; earlier
 * history is never backfilled. Always inserted, never merged (the table refuses updates).
 */
@Entity
@Table(schema = "inventory", name = "inventory_activation")
public class InventoryActivation implements Persistable<UUID> {

    @Id
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private Instant activeFrom;

    @Column(updatable = false)
    private UUID activatedByAccountId;

    @Column(nullable = false, updatable = false)
    private Instant activatedAt;

    @Transient
    private boolean fresh;

    protected InventoryActivation() {
        // JPA
    }

    public static InventoryActivation of(UUID storeId, Instant activeFrom, UUID accountId, Instant now) {
        InventoryActivation activation = new InventoryActivation();
        activation.storeId = storeId;
        activation.activeFrom = activeFrom;
        activation.activatedByAccountId = accountId;
        activation.activatedAt = now;
        activation.fresh = true;
        return activation;
    }

    @Override
    public UUID getId() {
        return storeId;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Instant getActiveFrom() {
        return activeFrom;
    }

    public UUID getActivatedByAccountId() {
        return activatedByAccountId;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }
}
