package com.xavier.backend.settlement;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The application clock of the settlement test context: real time (microsecond precise) shifted by an offset a test
 * may {@link #advance} (e.g. past the sweeper's thresholds) or {@link #moveTo} (e.g. next to a store's business-day
 * cutoff), and must {@link #reset} afterwards.
 */
public final class SettlementTestClock extends Clock {

    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration by) {
        offset = offset.plus(by);
    }

    /** Shifts the clock so that it reads {@code instant} now, and runs on from there. */
    public void moveTo(Instant instant) {
        offset = Duration.between(Instant.now(Clock.systemUTC()), instant);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public Instant instant() {
        return Instant.now(Clock.systemUTC()).plus(offset).truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("the application clock is UTC");
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        SettlementTestClock settlementTestClock() {
            return new SettlementTestClock();
        }
    }
}
