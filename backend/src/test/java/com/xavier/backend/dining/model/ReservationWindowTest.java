package com.xavier.backend.dining.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** P47: "reserved" from the window before the start until the no-show grace after it (default 120 / 30 min). */
class ReservationWindowTest {

    static final Instant STARTS = Instant.parse("2026-11-20T19:00:00Z");
    static final ReservationWindow DEFAULT = new ReservationWindow(120, 30);

    static boolean shown(ReservationWindow window, Duration fromStart) {
        Instant now = STARTS.plus(fromStart);
        return !STARTS.isBefore(window.earliestStart(now)) && !STARTS.isAfter(window.latestStart(now))
                && window.indicates(STARTS, now);
    }

    @Test
    void theIndicatorOpensAtTheWindowBeforeTheStart() {
        assertThat(DEFAULT.indicates(STARTS, STARTS.minus(Duration.ofMinutes(121)))).isFalse();
        assertThat(DEFAULT.indicates(STARTS, STARTS.minus(Duration.ofMinutes(120)))).isTrue();
        assertThat(shown(DEFAULT, Duration.ofMinutes(-120))).isTrue();
    }

    @Test
    void theIndicatorStaysUntilTheNoShowGraceEnds() {
        assertThat(DEFAULT.indicates(STARTS, STARTS.plus(Duration.ofMinutes(29)))).isTrue();
        assertThat(shown(DEFAULT, Duration.ofMinutes(29))).isTrue();
        assertThat(DEFAULT.indicates(STARTS, STARTS.plus(Duration.ofMinutes(30)))).isFalse();
    }

    @Test
    void theNoShowIsDueOnceTheGraceHasPassed() {
        assertThat(DEFAULT.noShowDue(STARTS, STARTS.plus(Duration.ofMinutes(29)))).isFalse();
        assertThat(DEFAULT.noShowDue(STARTS, STARTS.plus(Duration.ofMinutes(30)))).isTrue();
    }

    @Test
    void aStoreSettingNarrowsTheWindow() {
        ReservationWindow narrow = new ReservationWindow(15, 0);

        assertThat(narrow.indicates(STARTS, STARTS.minus(Duration.ofMinutes(16)))).isFalse();
        assertThat(narrow.indicates(STARTS, STARTS.minus(Duration.ofMinutes(15)))).isTrue();
        assertThat(narrow.indicates(STARTS, STARTS)).isFalse();
    }
}
