package com.xavier.backend.dining.service;

import com.xavier.backend.dining.model.ReservationWindow;
import com.xavier.backend.dining.repository.NextReservation;
import com.xavier.backend.dining.repository.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The table map's "reserved" indicator (D126, P47): the earliest BOOKED reservation of a table that starts within
 * the store's window from now, until its no-show grace has passed. Independent of occupation, lock and subtotal
 * (§8.4). Callers run it in their (read-only) transaction.
 */
@Component
public class ReservationIndicator {

    private final ReservationRepository reservations;
    private final DiningSettingsService settings;
    private final Clock clock;

    ReservationIndicator(ReservationRepository reservations, DiningSettingsService settings, Clock clock) {
        this.reservations = reservations;
        this.settings = settings;
        this.clock = clock;
    }

    /** Table id → the start of its indicated reservation, for every indicated table of the store. */
    public Map<UUID, Instant> nextByTable(UUID storeId) {
        ReservationWindow window = settings.windowOf(storeId);
        Instant now = clock.instant();
        return reservations.nextByTable(storeId, window.earliestStart(now), window.latestStart(now)).stream()
                .collect(Collectors.toMap(NextReservation::tableId, NextReservation::startsAt));
    }

    /** The start of the table's indicated reservation, or {@code null}. */
    public Instant nextFor(UUID storeId, UUID tableId) {
        ReservationWindow window = settings.windowOf(storeId);
        Instant now = clock.instant();
        return reservations.nextForTable(tableId, window.earliestStart(now), window.latestStart(now)).orElse(null);
    }
}
