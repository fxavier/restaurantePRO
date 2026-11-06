package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import com.xavier.backend.catalog.model.dto.AutomaticItemSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A line the orders module adds as a draft when an order opens (D109), for one store or every store ({@code storeId}
 * null). Only ACTIVE automatic items are frozen into a release. Only {@code ORDER_OPEN} names an order mode; its
 * {@code null} mode means every mode.
 */
@Entity
@Table(schema = "catalog", name = "automatic_item")
public class AutomaticItem {

    /** What an editor sets on create and update. */
    public record Details(AutomaticTrigger trigger, String mode, ItemLine line, boolean active) {
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(updatable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false, length = 30)
    private AutomaticTrigger trigger;

    @Column(length = 20)
    private String mode;

    @Embedded
    private ItemLine line;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected AutomaticItem() {
        // JPA
    }

    public static AutomaticItem create(UUID storeId, Details details, Instant now) {
        AutomaticItem automatic = new AutomaticItem();
        automatic.storeId = storeId;
        automatic.createdAt = now;
        automatic.update(details, now);
        return automatic;
    }

    /** @throws InvalidCatalogEntryException (422) for a mode on a table trigger or a quantity not above zero */
    public void update(Details details, Instant now) {
        AutomaticTrigger newTrigger = CatalogText.required(details.trigger(), "trigger");
        if (newTrigger != AutomaticTrigger.ORDER_OPEN && details.mode() != null) {
            throw new InvalidCatalogEntryException("Only an ORDER_OPEN automatic item names an order mode");
        }
        ItemLine newLine = CatalogText.required(details.line(), "line");
        if (newLine.getQuantity().signum() <= 0) {
            throw new InvalidCatalogEntryException("The quantity of an automatic item must be more than zero");
        }
        this.trigger = newTrigger;
        this.mode = details.mode();
        this.line = newLine;
        this.active = details.active();
        this.updatedAt = now;
    }

    public AutomaticItemSummary toSummary() {
        return new AutomaticItemSummary(id, storeId, trigger.name(), mode, line.getItemId(), line.getVariantId(),
                line.getQuantity(), active, version);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public AutomaticTrigger getTrigger() {
        return trigger;
    }

    public String getMode() {
        return mode;
    }

    public ItemLine getLine() {
        return line;
    }

    public boolean isActive() {
        return active;
    }

    public Long getVersion() {
        return version;
    }
}
