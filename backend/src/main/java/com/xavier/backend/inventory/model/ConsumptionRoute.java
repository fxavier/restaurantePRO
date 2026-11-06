package com.xavier.backend.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Where the lines of a production destination consume (D114): the bar consumes from the bar store-room. A route
 * without a destination is the store's default. The warehouse is of the route's store.
 */
@Entity
@Table(schema = "inventory", name = "consumption_route")
public class ConsumptionRoute {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(updatable = false, length = 40)
    private String destinationCode;

    @Column(nullable = false)
    private UUID warehouseId;

    @Version
    private Long version;

    protected ConsumptionRoute() {
        // JPA
    }

    public static ConsumptionRoute create(UUID storeId, String destinationCode, UUID warehouseId) {
        ConsumptionRoute route = new ConsumptionRoute();
        route.storeId = storeId;
        route.destinationCode = destinationCode == null || destinationCode.isBlank() ? null : destinationCode.strip();
        route.warehouseId = warehouseId;
        return route;
    }

    public void routeTo(UUID warehouseId) {
        this.warehouseId = warehouseId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getDestinationCode() {
        return destinationCode;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
