package com.xavier.backend.dining.model;

import com.xavier.backend.dining.model.dto.DiningSettingsView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** A store's reservation settings (P47). A store without a row uses {@link ReservationWindow#DEFAULT}. */
@Entity
@Table(schema = "dining", name = "dining_settings")
public class DiningSettings {

    @Id
    private UUID storeId;

    @Column(nullable = false)
    private int reservationWindowMinutes;

    @Column(nullable = false)
    private int noShowGraceMinutes;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected DiningSettings() {
        // JPA
    }

    public static DiningSettings create(UUID storeId, ReservationWindow window, Instant now) {
        DiningSettings settings = new DiningSettings();
        settings.storeId = storeId;
        settings.change(window, now);
        return settings;
    }

    public void change(ReservationWindow window, Instant now) {
        this.reservationWindowMinutes = window.windowMinutes();
        this.noShowGraceMinutes = window.graceMinutes();
        this.updatedAt = now;
    }

    public ReservationWindow window() {
        return new ReservationWindow(reservationWindowMinutes, noShowGraceMinutes);
    }

    public DiningSettingsView toView() {
        return new DiningSettingsView(storeId, reservationWindowMinutes, noShowGraceMinutes, version);
    }

    public UUID getStoreId() {
        return storeId;
    }

    public Long getVersion() {
        return version;
    }
}
