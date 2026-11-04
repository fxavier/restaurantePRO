package com.xavier.backend.organization.model;

import com.xavier.backend.organization.BusinessDays;
import com.xavier.backend.organization.exception.InvalidStoreException;
import com.xavier.backend.organization.model.dto.StoreSummary;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * An operating unit of the company (spec §2, §8.2). {@code id} scopes operations and permissions. Money
 * uses the company currency (single currency, spec §8.4), so the store has no currency of its own.
 */
@Entity
@Table(schema = "organization", name = "store")
public class Store {

    public static final LocalTime DEFAULT_BUSINESS_DAY_CUTOFF = LocalTime.of(5, 0);

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 64)
    private String timezone;

    @Column(nullable = false)
    private LocalTime businessDayCutoff;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Store() {
        // JPA
    }

    /** A new, active store. A {@code null} cutoff means {@link #DEFAULT_BUSINESS_DAY_CUTOFF}. */
    public static Store create(String code, String name, String timezone, LocalTime businessDayCutoff, Instant now) {
        Store store = new Store();
        store.createdAt = now;
        store.apply(code, name, timezone,
                businessDayCutoff == null ? DEFAULT_BUSINESS_DAY_CUTOFF : businessDayCutoff, true, now);
        return store;
    }

    public void update(String code, String name, String timezone, LocalTime businessDayCutoff, boolean active,
                       Instant now) {
        apply(code, name, timezone, businessDayCutoff, active, now);
    }

    /** The business day {@code instant} belongs to in this store (spec §4). */
    public LocalDate businessDayOf(Instant instant) {
        return BusinessDays.of(instant, getTimezone(), businessDayCutoff);
    }

    private void apply(String code, String name, String timezone, LocalTime businessDayCutoff, boolean active,
                       Instant now) {
        this.code = requireText(code, "code");
        this.name = requireText(name, "name");
        this.timezone = requireRegion(timezone);
        this.businessDayCutoff = requirePresent(businessDayCutoff);
        this.active = active;
        this.updatedAt = now;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidStoreException(field + " is required");
        }
        return value.strip();
    }

    private static String requireRegion(String timezone) {
        if (timezone == null || !ZoneId.getAvailableZoneIds().contains(timezone)) {
            throw new InvalidStoreException("timezone '" + timezone + "' is not a region id (e.g. Africa/Maputo)");
        }
        return timezone;
    }

    private static LocalTime requirePresent(LocalTime cutoff) {
        if (cutoff == null) {
            throw new InvalidStoreException("businessDayCutoff is required");
        }
        return cutoff;
    }

    /** {@code currency} is the company currency (single currency, spec §8.4). */
    public StoreSummary toSummary(Currency currency) {
        return new StoreSummary(id, code, name, getTimezone(), businessDayCutoff, currency, active, version);
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

    public ZoneId getTimezone() {
        return ZoneId.of(timezone);
    }

    public LocalTime getBusinessDayCutoff() {
        return businessDayCutoff;
    }

    public boolean isActive() {
        return active;
    }

    public Long getVersion() {
        return version;
    }
}
