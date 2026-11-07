package com.xavier.backend.promotions;

import static com.xavier.backend.promotions.EngineFixtures.FAMILY;
import static com.xavier.backend.promotions.EngineFixtures.families;
import static com.xavier.backend.promotions.EngineFixtures.line;
import static com.xavier.backend.promotions.EngineFixtures.mzn;
import static com.xavier.backend.promotions.EngineFixtures.percentOff;
import static com.xavier.backend.promotions.EngineFixtures.request;
import static com.xavier.backend.promotions.EngineFixtures.snapshot;
import static com.xavier.backend.promotions.EngineFixtures.withWindows;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.promotions.model.dto.PromotionSnapshot;
import com.xavier.backend.promotions.model.dto.PromotionSnapshot.Window;
import com.xavier.backend.shared.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D103, A38: a happy hour is read in the store's zone at the confirmation instant. Instants come from fixed clocks,
 * never from the wall clock.
 */
class HappyHourWindowTest {

    static final ZoneId MAPUTO = ZoneId.of("Africa/Maputo");
    static final ZoneId LISBON = ZoneId.of("Europe/Lisbon");
    static final List<Integer> EVERY_DAY = List.of(1, 2, 3, 4, 5, 6, 7);

    /** 20 % on family Drinks, 18:00–20:00 every day. */
    static final PromotionSnapshot HAPPY_HOUR = snapshot(withWindows(percentOff("HH", "20", 1, families(FAMILY)),
            new Window(EVERY_DAY, LocalTime.of(18, 0), LocalTime.of(20, 0))));

    @Test
    void theWindowStartsInclusiveAndEndsExclusiveInStoreTime() {
        assertThat(priceAt(HAPPY_HOUR, local("2026-11-06T17:59:59", MAPUTO), MAPUTO)).isEqualTo(mzn("10.00"));
        assertThat(priceAt(HAPPY_HOUR, local("2026-11-06T18:00:00", MAPUTO), MAPUTO)).isEqualTo(mzn("8.00"));
        assertThat(priceAt(HAPPY_HOUR, local("2026-11-06T19:59:00", MAPUTO), MAPUTO)).isEqualTo(mzn("8.00"));
        assertThat(priceAt(HAPPY_HOUR, local("2026-11-06T20:00:00", MAPUTO), MAPUTO)).isEqualTo(mzn("10.00"));
    }

    @Test
    void theSameInstantIsReadInTheStoresZone() {
        Instant at = local("2026-11-06T19:00:00", MAPUTO); // 17:00 in Lisbon (UTC+0 in November)

        assertThat(priceAt(HAPPY_HOUR, at, MAPUTO)).isEqualTo(mzn("8.00"));
        assertThat(priceAt(HAPPY_HOUR, at, LISBON)).isEqualTo(mzn("10.00"));
    }

    /** Friday 22:00 to 02:00: the window belongs to the day it starts on. 2026-11-06 is a Friday. */
    @Test
    void aWindowCrossingMidnightRunsIntoTheNextDay() {
        PromotionSnapshot lateFriday = snapshot(withWindows(percentOff("LATE", "20", 1, families(FAMILY)),
                new Window(List.of(5), LocalTime.of(22, 0), LocalTime.of(2, 0))));

        assertThat(priceAt(lateFriday, local("2026-11-06T21:59:59", MAPUTO), MAPUTO)).isEqualTo(mzn("10.00"));
        assertThat(priceAt(lateFriday, local("2026-11-06T23:30:00", MAPUTO), MAPUTO)).isEqualTo(mzn("8.00"));
        assertThat(priceAt(lateFriday, local("2026-11-07T01:59:59", MAPUTO), MAPUTO)).isEqualTo(mzn("8.00"));
        assertThat(priceAt(lateFriday, local("2026-11-07T02:00:00", MAPUTO), MAPUTO)).isEqualTo(mzn("10.00"));
        assertThat(priceAt(lateFriday, local("2026-11-07T23:00:00", MAPUTO), MAPUTO)).isEqualTo(mzn("10.00"));
        assertThat(priceAt(lateFriday, local("2026-11-06T01:00:00", MAPUTO), MAPUTO)).isEqualTo(mzn("10.00"));
    }

    @Test
    void onlyTheListedDaysOfTheWeekApply() {
        PromotionSnapshot weekdays = snapshot(withWindows(percentOff("WD", "20", 1, families(FAMILY)),
                new Window(List.of(1, 2, 3, 4, 5), LocalTime.of(18, 0), LocalTime.of(20, 0))));

        assertThat(priceAt(weekdays, local("2026-11-06T19:00:00", MAPUTO), MAPUTO)).isEqualTo(mzn("8.00"));
        assertThat(priceAt(weekdays, local("2026-11-07T19:00:00", MAPUTO), MAPUTO)).isEqualTo(mzn("10.00"));
    }

    /** Lisbon springs forward on 2026-03-29 at 01:00 UTC (01:00 WET becomes 02:00 WEST). */
    @Test
    void aWindowFollowsTheWallClockAcrossTheSpringForwardGap() {
        PromotionSnapshot earlyHours = snapshot(withWindows(percentOff("EH", "20", 1, families(FAMILY)),
                new Window(EVERY_DAY, LocalTime.of(0, 30), LocalTime.of(2, 30))));

        assertThat(priceAt(earlyHours, Instant.parse("2026-03-29T00:45:00Z"), LISBON)).isEqualTo(mzn("8.00"));
        assertThat(priceAt(earlyHours, Instant.parse("2026-03-29T01:15:00Z"), LISBON)).isEqualTo(mzn("8.00"));
        assertThat(priceAt(earlyHours, Instant.parse("2026-03-29T01:30:00Z"), LISBON)).isEqualTo(mzn("10.00"));
    }

    /** Lisbon falls back on 2026-10-25 at 01:00 UTC (02:00 WEST becomes 01:00 WET): 01:00–01:30 happens twice. */
    @Test
    void aWindowAppliesToBothOccurrencesOfARepeatedHour() {
        PromotionSnapshot repeated = snapshot(withWindows(percentOff("RP", "20", 1, families(FAMILY)),
                new Window(EVERY_DAY, LocalTime.of(1, 0), LocalTime.of(1, 30))));

        assertThat(priceAt(repeated, Instant.parse("2026-10-25T00:15:00Z"), LISBON)).isEqualTo(mzn("8.00"));
        assertThat(priceAt(repeated, Instant.parse("2026-10-25T00:45:00Z"), LISBON)).isEqualTo(mzn("10.00"));
        assertThat(priceAt(repeated, Instant.parse("2026-10-25T01:15:00Z"), LISBON)).isEqualTo(mzn("8.00"));
        assertThat(priceAt(repeated, Instant.parse("2026-10-25T01:30:00Z"), LISBON)).isEqualTo(mzn("10.00"));
    }

    private static Instant local(String dateTime, ZoneId zone) {
        return Clock.fixed(LocalDateTime.parse(dateTime).atZone(zone).toInstant(), ZoneOffset.UTC).instant();
    }

    private static Money priceAt(PromotionSnapshot snapshot, Instant at, ZoneId zone) {
        return PromotionEngine.evaluate(snapshot, request(at, line("10.00")), zone).lines().getFirst().unitPrice();
    }
}
