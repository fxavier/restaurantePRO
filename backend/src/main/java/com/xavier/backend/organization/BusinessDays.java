package com.xavier.backend.organization;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;

/**
 * The business-day rule (spec §4, D16): a trading day starts at the store's cutoff, so it may cross
 * midnight. Pure function; the store supplies its zone and cutoff.
 */
public final class BusinessDays {

    private BusinessDays() {
    }

    /**
     * The business day {@code instant} belongs to. Day D starts at the first instant the local clock
     * shows {@code cutoff} on date D; a cutoff that falls in a DST gap starts the day when the clock
     * jumps past it. Comparing instants, not local times, keeps the day from going backwards when a
     * DST fall-back repeats the cutoff hour.
     */
    public static LocalDate of(Instant instant, ZoneId zone, LocalTime cutoff) {
        LocalDate localDate = LocalDate.ofInstant(instant, zone);
        return instant.isBefore(startOf(localDate, zone, cutoff)) ? localDate.minusDays(1) : localDate;
    }

    private static Instant startOf(LocalDate day, ZoneId zone, LocalTime cutoff) {
        LocalDateTime start = day.atTime(cutoff);
        ZoneOffsetTransition gap = zone.getRules().getTransition(start);
        if (gap != null && gap.isGap()) {
            return gap.getInstant(); // the clock jumps over the cutoff: the day starts at the jump
        }
        return ZonedDateTime.of(start, zone).toInstant(); // in an overlap: the earlier offset
    }
}
