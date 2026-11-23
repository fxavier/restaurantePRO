package com.xavier.backend.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A fixed clock tests can move forward (e.g. past a support grant's end). Time only moves forward, so tests sharing
 * the context never see it go back. A copy of organization's test clock, which is that module's own.
 */
public final class MutableClock extends Clock {

    /** Atomic updates: two threads advancing at once must not lose a step. */
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.now(Clock.systemUTC()).truncatedTo(ChronoUnit.MICROS));

    public void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    @Override
    public Instant instant() {
        return now.get();
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
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }
}
