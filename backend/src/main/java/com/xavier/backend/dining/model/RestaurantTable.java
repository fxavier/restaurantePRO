package com.xavier.backend.dining.model;

import com.xavier.backend.dining.exception.AreaNotInStoreException;
import com.xavier.backend.dining.exception.InvalidDiningSetupException;
import com.xavier.backend.dining.model.dto.RestaurantTableSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A physical table in a room area of the same store (CONTEXT.md: RestaurantTable, spec §8.2). Never deleted:
 * R2 occupations and orders refer to it, so it is deactivated instead.
 */
@Entity
@Table(schema = "dining", name = "restaurant_table")
public class RestaurantTable {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false)
    private UUID areaId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false)
    private int seats;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RestaurantTable() {
        // JPA
    }

    public static RestaurantTable create(UUID storeId, RoomArea area, String code, int seats, Instant now) {
        RestaurantTable table = new RestaurantTable();
        table.storeId = storeId;
        table.createdAt = now;
        table.update(area, code, seats, true, now);
        return table;
    }

    public void update(RoomArea area, String code, int seats, boolean active, Instant now) {
        if (!area.getStoreId().equals(storeId)) {
            throw new AreaNotInStoreException(area.getId(), storeId);
        }
        if (seats < 1) {
            throw new InvalidDiningSetupException("seats must be at least 1");
        }
        this.areaId = area.getId();
        this.code = DiningValues.requireText(code, "code");
        this.seats = seats;
        this.active = active;
        this.updatedAt = now;
    }

    public RestaurantTableSummary toSummary() {
        return new RestaurantTableSummary(id, storeId, areaId, code, seats, active, version);
    }

    public UUID getId() {
        return id;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public UUID getAreaId() {
        return areaId;
    }

    public String getCode() {
        return code;
    }

    public int getSeats() {
        return seats;
    }

    public boolean isActive() {
        return active;
    }

    public Long getVersion() {
        return version;
    }
}
