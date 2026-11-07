package com.xavier.backend.inventory.preparation.model;

import com.xavier.backend.inventory.preparation.exception.PreparationConflictException;
import com.xavier.backend.inventory.preparation.model.dto.PreparationView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A preparation (D121): a preparation recipe version made in one warehouse for an output quantity, in the prepared
 * item's base unit. Confirming it is what posts stock (the service does, in the same transaction); a confirmed or
 * cancelled preparation is a fact (trigger).
 */
@Entity
@Table(schema = "inventory", name = "preparation")
public class Preparation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(nullable = false, updatable = false)
    private UUID recipeVersionId;

    @Column(nullable = false, updatable = false)
    private UUID outputStockItemId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal outputQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private PreparationStatus status;

    @Column(updatable = false)
    private String notes;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, updatable = false, length = 10)
    private String createdByActorKind;

    @Column(updatable = false)
    private UUID createdByActorId;

    private Instant confirmedAt;

    @Column(length = 10)
    private String confirmedByActorKind;

    private UUID confirmedByActorId;

    private Instant cancelledAt;

    @Version
    private Long version;

    protected Preparation() {
        // JPA
    }

    public static Preparation draft(UUID storeId, UUID warehouseId, UUID recipeVersionId, UUID outputStockItemId,
                                    BigDecimal outputQuantity, String notes, String actorKind, UUID actorId,
                                    Instant now) {
        Preparation preparation = new Preparation();
        preparation.storeId = storeId;
        preparation.warehouseId = warehouseId;
        preparation.recipeVersionId = recipeVersionId;
        preparation.outputStockItemId = outputStockItemId;
        preparation.outputQuantity = outputQuantity;
        preparation.notes = notes;
        preparation.status = PreparationStatus.DRAFT;
        preparation.createdAt = now;
        preparation.createdByActorKind = actorKind;
        preparation.createdByActorId = actorId;
        return preparation;
    }

    public void confirm(String actorKind, UUID actorId, Instant now) {
        requireDraft("be confirmed");
        status = PreparationStatus.CONFIRMED;
        confirmedAt = now;
        confirmedByActorKind = actorKind;
        confirmedByActorId = actorId;
    }

    public void cancel(Instant now) {
        requireDraft("be cancelled");
        status = PreparationStatus.CANCELLED;
        cancelledAt = now;
    }

    public void requireDraft(String action) {
        if (status != PreparationStatus.DRAFT) {
            throw PreparationConflictException.state(id, status, action);
        }
    }

    public PreparationView toView() {
        return new PreparationView(id, storeId, warehouseId, recipeVersionId, outputStockItemId, outputQuantity,
                status.name(), notes, createdAt, confirmedAt, cancelledAt, version == null ? 0 : version);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public UUID getRecipeVersionId() {
        return recipeVersionId;
    }

    public UUID getOutputStockItemId() {
        return outputStockItemId;
    }

    public BigDecimal getOutputQuantity() {
        return outputQuantity;
    }
}
