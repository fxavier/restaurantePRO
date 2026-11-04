package com.xavier.backend.organization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BusinessDaysTest {

    static final ZoneId MAPUTO = ZoneId.of("Africa/Maputo"); // UTC+2, no DST
    static final ZoneId LISBON = ZoneId.of("Europe/Lisbon"); // DST: +00 <-> +01
    static final LocalTime FIVE_AM = LocalTime.of(5, 0);

    @Test
    void anInstantAfterMidnightButBeforeTheCutoffBelongsToThePreviousDay() {
        Instant twoAmLocal = Instant.parse("2026-11-04T00:00:00Z"); // 02:00 in Maputo

        assertThat(BusinessDays.of(twoAmLocal, MAPUTO, FIVE_AM)).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void theCutoffInstantStartsTheNewDay() {
        Instant fiveAmLocal = Instant.parse("2026-11-04T03:00:00Z");

        assertThat(BusinessDays.of(fiveAmLocal, MAPUTO, FIVE_AM)).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(BusinessDays.of(fiveAmLocal.minusNanos(1), MAPUTO, FIVE_AM)).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void whenTheClockFallsBackTheRepeatedHourDoesNotReopenThePreviousDay() {
        // Lisbon 2026-10-25: 02:00 WEST -> 01:00 WET, so 01:00-02:00 local happens twice.
        // The day starts at the first 01:30 (00:30Z); the second 01:20 (01:20Z) is after it.
        LocalTime cutoff = LocalTime.of(1, 30);

        assertThat(BusinessDays.of(Instant.parse("2026-10-25T00:29:59Z"), LISBON, cutoff))
                .isEqualTo(LocalDate.of(2026, 10, 24));
        assertThat(BusinessDays.of(Instant.parse("2026-10-25T00:30:00Z"), LISBON, cutoff))
                .isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(BusinessDays.of(Instant.parse("2026-10-25T01:20:00Z"), LISBON, cutoff))
                .isEqualTo(LocalDate.of(2026, 10, 25));
    }

    @Test
    void aCutoffInsideTheSpringForwardGapStartsTheDayWhenTheClockJumps() {
        // Lisbon 2026-03-29: 01:00 WET -> 02:00 WEST, so 01:30 local never happens.
        LocalTime cutoff = LocalTime.of(1, 30);

        assertThat(BusinessDays.of(Instant.parse("2026-03-29T00:59:59Z"), LISBON, cutoff))
                .isEqualTo(LocalDate.of(2026, 3, 28));
        assertThat(BusinessDays.of(Instant.parse("2026-03-29T01:00:00Z"), LISBON, cutoff))
                .isEqualTo(LocalDate.of(2026, 3, 29));
    }
}
