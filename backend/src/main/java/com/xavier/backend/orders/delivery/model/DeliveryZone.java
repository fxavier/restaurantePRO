package com.xavier.backend.orders.delivery.model;

import com.xavier.backend.orders.delivery.model.dto.ZoneView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A delivery zone of a store (D128): the catalog FEE item a delivery order to it gets as an automatic draft. Its code
 * never changes (deliveries snapshot it); an inactive zone takes no new delivery.
 */
@Entity
@Table(schema = "orders", name = "delivery_zone")
public class DeliveryZone {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, updatable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false)
    private UUID feeItemId;

    private UUID feeVariantId;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    protected DeliveryZone() {
        // JPA
    }

    public static DeliveryZone create(UUID storeId, String code, String name, UUID feeItemId, UUID feeVariantId) {
        DeliveryZone zone = new DeliveryZone();
        zone.storeId = Objects.requireNonNull(storeId, "storeId");
        zone.code = Objects.requireNonNull(code, "code").strip();
        zone.change(name, feeItemId, feeVariantId, true);
        return zone;
    }

    public void change(String name, UUID feeItemId, UUID feeVariantId, boolean active) {
        this.name = Objects.requireNonNull(name, "name").strip();
        this.feeItemId = Objects.requireNonNull(feeItemId, "feeItemId");
        this.feeVariantId = feeVariantId;
        this.active = active;
    }

    public ZoneView toView() {
        return new ZoneView(id, storeId, code, name, feeItemId, feeVariantId, active, getVersion());
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getCode() {
        return code;
    }

    public UUID getFeeItemId() {
        return feeItemId;
    }

    public UUID getFeeVariantId() {
        return feeVariantId;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
