package com.xavier.backend.inventory.consumption.model;

import com.xavier.backend.inventory.consumption.exception.ConsumptionConflictException;
import com.xavier.backend.inventory.consumption.model.dto.ExceptionView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A sold line or menu component that could not be consumed (D116): visible, never a silent skip, and resolved by a
 * reprocess once its recipe, route or stock item is fixed. Created by consumption (insert-or-ignore per source and
 * kind), so it is read here and only resolved.
 */
@Entity
@Table(schema = "inventory", name = "consumption_exception")
public class ConsumptionException {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false, length = 30)
    private String sourceType;

    @Column(nullable = false, updatable = false)
    private UUID sourceId;

    @Column(updatable = false)
    private UUID itemId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 25)
    private ExceptionKind kind;

    @Column(updatable = false)
    private UUID batchId;

    @Column(updatable = false)
    private UUID lineId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant resolvedAt;

    private String resolution;

    @Column(nullable = false)
    private boolean lateRecipe;

    protected ConsumptionException() {
        // JPA
    }

    /** Resolved by a reprocess; {@code late} when it used a recipe version activated after the confirmation. */
    public void resolve(String how, boolean late, Instant now) {
        if (resolvedAt != null) {
            throw ConsumptionConflictException.alreadyResolved(id);
        }
        resolution = how;
        lateRecipe = late;
        resolvedAt = now;
    }

    public ExceptionView toView() {
        return new ExceptionView(id, storeId, sourceType, sourceId, itemId, kind.name(), batchId, lineId, createdAt,
                resolvedAt, resolution, lateRecipe);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public UUID getSourceId() {
        return sourceId;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public UUID getLineId() {
        return lineId;
    }

    public boolean isOpen() {
        return resolvedAt == null;
    }
}
