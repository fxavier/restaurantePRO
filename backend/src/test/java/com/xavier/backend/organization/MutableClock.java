package com.xavier.backend.organization;

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
 * A fixed clock tests can move forward (e.g. past an enrollment code's expiry). Time only moves forward, so
 * tests sharing the context never see it go back.
 */
public final class MutableClock extends Clock {

    /** Atomic updates: two threads advancing at once must not lose a step or move the clock back. */
    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.now(Clock.systemUTC()).truncatedTo(ChronoUnit.MICROS));

    public void advance(Duration duration) {
        now.updateAndGet(current -> current.plus(duration));
    }

    /** Moves the clock to {@code instant} if that is later; never back. */
    public void advanceTo(Instant instant) {
        now.updateAndGet(current -> instant.isAfter(current) ? instant : current);
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
