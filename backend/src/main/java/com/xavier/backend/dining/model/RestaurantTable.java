package com.xavier.backend.dining.model;

import com.xavier.backend.dining.exception.AreaNotInStoreException;
import com.xavier.backend.dining.exception.InvalidDiningSetupException;
import com.xavier.backend.dining.exception.TableUnavailableException;
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

    private Instant lockedAt;

    private UUID lockedByEmployeeId;

    @Column(length = 200)
    private String lockReason;

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

    /**
     * Locks the table (spec §4: a locked table takes no new occupation, lines, rounds or incoming transfers; payments
     * and close stay allowed). Locking a locked table keeps the first lock.
     *
     * @return whether the table changed
     */
    public boolean lock(UUID employeeId, String reason, Instant now) {
        String lockReasonText = DiningValues.requireText(reason, "reason");
        if (isLocked()) {
            return false;
        }
        this.lockedAt = now;
        this.lockedByEmployeeId = employeeId;
        this.lockReason = lockReasonText;
        this.updatedAt = now;
        return true;
    }

    /** @return whether the table changed (unlocking an unlocked table changes nothing) */
    public boolean unlock(Instant now) {
        if (!isLocked()) {
            return false;
        }
        this.lockedAt = null;
        this.lockedByEmployeeId = null;
        this.lockReason = null;
        this.updatedAt = now;
        return true;
    }

    /**
     * The table may take an occupation in the session's store: same store, table and {@code area} (its own) active,
     * not locked. Whether it is already occupied is decided by the open occupation, not the table.
     */
    public void requireOpenable(UUID sessionStoreId, RoomArea area) {
        if (!storeId.equals(sessionStoreId)) {
            throw TableUnavailableException.storeMismatch(id);
        }
        if (!active || !area.isActive()) {
            throw TableUnavailableException.inactive(id);
        }
        if (isLocked()) {
            throw TableUnavailableException.locked(id);
        }
    }

    public boolean isLocked() {
        return lockedAt != null;
    }

    public RestaurantTableSummary toSummary() {
        return new RestaurantTableSummary(id, storeId, areaId, code, seats, active, isLocked(), version);
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

    public Instant getLockedAt() {
        return lockedAt;
    }

    public UUID getLockedByEmployeeId() {
        return lockedByEmployeeId;
    }

    public String getLockReason() {
        return lockReason;
    }

    public Long getVersion() {
        return version;
    }
}
