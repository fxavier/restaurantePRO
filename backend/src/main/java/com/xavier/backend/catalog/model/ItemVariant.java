package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.model.dto.ItemVariantSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A variant of an item (size, flavour…); its code is unique within the item. */
@Entity
@Table(schema = "catalog", name = "item_variant")
public class ItemVariant {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID itemId;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ItemVariant() {
        // JPA
    }

    public static ItemVariant create(UUID itemId, String code, String name, Instant now) {
        ItemVariant variant = new ItemVariant();
        variant.itemId = CatalogText.required(itemId, "itemId");
        variant.createdAt = now;
        variant.update(code, name, now);
        return variant;
    }

    public void update(String code, String name, Instant now) {
        this.code = CatalogText.required(code, "code");
        this.name = CatalogText.required(name, "name");
        this.updatedAt = now;
    }

    public ItemVariantSummary toSummary() {
        return new ItemVariantSummary(id, itemId, code, name, version);
    }

    public UUID getId() {
        return id;
    }

    public UUID getItemId() {
        return itemId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Long getVersion() {
        return version;
    }
}
