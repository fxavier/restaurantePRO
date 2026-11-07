package com.xavier.backend.dining.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.dining.model.DiningSettings;
import com.xavier.backend.dining.model.ReservationWindow;
import com.xavier.backend.dining.model.dto.DiningSettingsRequest;
import com.xavier.backend.dining.model.dto.DiningSettingsView;
import com.xavier.backend.dining.repository.DiningSettingsRepository;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.persistence.OptimisticLocking;
import com.xavier.backend.shared.persistence.UniqueConstraints;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The per-store reservation settings (P47), {@code DINING_MANAGE} in the store. */
@Service
public class DiningSettingsService {

    static final String ENTITY = "DiningSettings";

    private final DiningSettingsRepository settings;
    private final OrganizationApi organization;
    private final AccessPolicy accessPolicy;
    private final DiningChanges changes;
    private final Clock clock;

    DiningSettingsService(DiningSettingsRepository settings, OrganizationApi organization, AccessPolicy accessPolicy,
                          DiningChanges changes, Clock clock) {
        this.settings = settings;
        this.organization = organization;
        this.accessPolicy = accessPolicy;
        this.changes = changes;
        this.clock = clock;
    }

    /** The store's window and grace, or the defaults. Needs no session. */
    @Transactional(readOnly = true)
    public ReservationWindow windowOf(UUID storeId) {
        return settings.findById(storeId).map(DiningSettings::window).orElse(ReservationWindow.DEFAULT);
    }

    @Transactional(readOnly = true)
    public DiningSettingsView get(UUID storeId) {
        accessPolicy.require(Permission.DINING_MANAGE, storeId);
        StoreChecks.requireExisting(organization, storeId);
        return settings.findById(storeId).map(DiningSettings::toView).orElseGet(() -> new DiningSettingsView(storeId,
                ReservationWindow.DEFAULT_WINDOW_MINUTES, ReservationWindow.DEFAULT_GRACE_MINUTES, null));
    }

    /**
     * Creates the store's row (the client read the defaults: {@code version} null) or changes it at the version
     * read; anything else is stale (409).
     */
    @Transactional
    public DiningSettingsView update(UUID storeId, DiningSettingsRequest request) {
        accessPolicy.require(Permission.DINING_MANAGE, storeId);
        StoreChecks.requireExisting(organization, storeId);
        ReservationWindow window = new ReservationWindow(request.reservationWindowMinutes(),
                request.noShowGraceMinutes());
        DiningSettings row = settings.findById(storeId).orElse(null);
        if (row == null) {
            if (request.version() != null) {
                throw new ObjectOptimisticLockingFailureException(DiningSettings.class, storeId);
            }
            row = DiningSettings.create(storeId, window, clock.instant());
        } else {
            OptimisticLocking.requireVersion(DiningSettings.class, storeId, row.getVersion(),
                    request.version() == null ? -1 : request.version());
            row.change(window, clock.instant());
        }
        DiningSettings saved = UniqueConstraints.saveAndFlush(settings, row,
                () -> new ObjectOptimisticLockingFailureException(DiningSettings.class, storeId), "dining_settings_pk");
        changes.record("DINING_SETTINGS_UPDATED", ENTITY, storeId, storeId, Map.of(
                "reservationWindowMinutes", window.windowMinutes(), "noShowGraceMinutes", window.graceMinutes()));
        return saved.toView();
    }
}
