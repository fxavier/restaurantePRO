package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.model.dto.BarcodeSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A barcode, unique in the installation, for an item or one of its variants. */
@Entity
@Table(schema = "catalog", name = "barcode")
public class Barcode {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false)
    private UUID itemId;

    private UUID variantId;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Barcode() {
        // JPA
    }

    /** {@code variantId} must be a variant of {@code itemId} (checked by the service and a composite FK). */
    public static Barcode create(String code, UUID itemId, UUID variantId, Instant now) {
        Barcode barcode = new Barcode();
        barcode.createdAt = now;
        barcode.update(code, itemId, variantId, now);
        return barcode;
    }

    public void update(String code, UUID itemId, UUID variantId, Instant now) {
        this.code = CatalogText.required(code, "code");
        this.itemId = CatalogText.required(itemId, "itemId");
        this.variantId = variantId;
        this.updatedAt = now;
    }

    public BarcodeSummary toSummary() {
        return new BarcodeSummary(id, code, itemId, variantId, version);
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public UUID getItemId() {
        return itemId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public Long getVersion() {
        return version;
    }
}
