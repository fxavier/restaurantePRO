package com.xavier.backend.dining.model;

import java.time.Duration;
import java.time.Instant;

/**
 * When a BOOKED reservation shows on the table map and when it becomes a no-show (P47), in absolute time (the
 * store's zone plays no part: minutes before and after an instant). A reservation is indicated while
 * {@code start - window ≤ now < start + grace}; at {@code start + grace} the no-show is due.
 */
public record ReservationWindow(int windowMinutes, int graceMinutes) {

    public static final int DEFAULT_WINDOW_MINUTES = 120;
    public static final int DEFAULT_GRACE_MINUTES = 30;
    public static final ReservationWindow DEFAULT =
            new ReservationWindow(DEFAULT_WINDOW_MINUTES, DEFAULT_GRACE_MINUTES);

    public boolean indicates(Instant startsAt, Instant now) {
        return startsAt.isAfter(earliestStart(now)) && !startsAt.isAfter(latestStart(now));
    }

    public boolean noShowDue(Instant startsAt, Instant now) {
        return !startsAt.isAfter(earliestStart(now));
    }

    /** Exclusive: a reservation that started at or before this is past its grace. */
    public Instant earliestStart(Instant now) {
        return now.minus(Duration.ofMinutes(graceMinutes));
    }

    /** Inclusive: the latest start indicated at {@code now}. */
    public Instant latestStart(Instant now) {
        return now.plus(Duration.ofMinutes(windowMinutes));
    }
}
