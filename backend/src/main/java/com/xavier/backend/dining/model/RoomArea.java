package com.xavier.backend.dining.model;

import com.xavier.backend.dining.exception.InvalidDiningSetupException;
import com.xavier.backend.dining.model.dto.RoomAreaSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A dining room, terrace or counter of a store (CONTEXT.md: RoomArea). Never deleted: future occupations and
 * reports refer to it, so it is deactivated instead.
 */
@Entity
@Table(schema = "dining", name = "room_area")
public class RoomArea {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID storeId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RoomArea() {
        // JPA
    }

    public static RoomArea create(UUID storeId, String code, String name, Instant now) {
        RoomArea area = new RoomArea();
        area.storeId = storeId;
        area.createdAt = now;
        area.update(code, name, true, now);
        return area;
    }

    public void update(String code, String name, boolean active, Instant now) {
        this.code = DiningValues.requireText(code, "code");
        this.name = DiningValues.requireText(name, "name");
        this.active = active;
        this.updatedAt = now;
    }

    public RoomAreaSummary toSummary() {
        return new RoomAreaSummary(id, storeId, code, name, active, version);
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

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public Long getVersion() {
        return version;
    }
}
