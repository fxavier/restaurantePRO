package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.model.dto.ItemKind;
import com.xavier.backend.catalog.model.dto.ItemSummary;
import jakarta.persistence.Column;
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
 * A commercial product (spec §5, §8.2). Company-wide; its price is per store (price lists). The production
 * destination is a code such as {@code KITCHEN} or {@code BAR} (production routing arrives in R2); none means
 * nothing to prepare. Its kind (D108) says whether it is a plain product, a MENU with component groups, or a FEE.
 */
@Entity
@Table(schema = "catalog", name = "item")
public class Item {

    /** The details an editor sets on create and update. */
    public record Details(String code, String name, UUID familyId, UUID unitId, UUID taxCategoryId,
                          String productionDestination, boolean soldByWeight) {
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private UUID familyId;

    @Column(nullable = false)
    private UUID unitId;

    @Column(nullable = false)
    private UUID taxCategoryId;

    @Column(length = 30)
    private String productionDestination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ItemAvailability availability;

    @Column(nullable = false)
    private boolean soldByWeight;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ItemKind kind;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Item() {
        // JPA
    }

    /** A new item is ACTIVE; {@code kind} {@code null} means STANDARD. */
    public static Item create(Details details, ItemKind kind, Instant now) {
        Item item = new Item();
        item.createdAt = now;
        item.kind = kind == null ? ItemKind.STANDARD : kind;
        item.update(details, ItemAvailability.ACTIVE, now);
        return item;
    }

    /** {@code kind} {@code null} keeps the current kind (R1–R3 clients do not send one). */
    public void update(Details details, ItemAvailability availability, ItemKind kind, Instant now) {
        if (kind != null) {
            this.kind = kind;
        }
        update(details, availability, now);
    }

    private void update(Details details, ItemAvailability availability, Instant now) {
        this.code = CatalogText.required(details.code(), "code");
        this.name = CatalogText.required(details.name(), "name");
        this.familyId = CatalogText.required(details.familyId(), "familyId");
        this.unitId = CatalogText.required(details.unitId(), "unitId");
        this.taxCategoryId = CatalogText.required(details.taxCategoryId(), "taxCategoryId");
        this.productionDestination = details.productionDestination() == null || details.productionDestination().isBlank()
                ? null : details.productionDestination().strip();
        this.soldByWeight = details.soldByWeight();
        this.availability = CatalogText.required(availability, "availability");
        this.updatedAt = now;
    }

    public boolean isActive() {
        return availability == ItemAvailability.ACTIVE;
    }

    public ItemSummary toSummary() {
        return new ItemSummary(id, code, name, familyId, unitId, taxCategoryId, productionDestination,
                availability.name(), soldByWeight, kind.name(), version);
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public UUID getUnitId() {
        return unitId;
    }

    public UUID getTaxCategoryId() {
        return taxCategoryId;
    }

    public String getProductionDestination() {
        return productionDestination;
    }

    public ItemAvailability getAvailability() {
        return availability;
    }

    public ItemKind getKind() {
        return kind;
    }

    public boolean isSoldByWeight() {
        return soldByWeight;
    }

    public Long getVersion() {
        return version;
    }
}
