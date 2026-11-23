package com.xavier.backend.orders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The application clock of the orders tests: the system clock, unless a test {@link #pin pins} it to an instant (a
 * promotion window boundary, D103) and {@link #release releases} it in a {@code finally}. Pinned instants are chosen far
 * from the 05:00 business-day cutoff.
 */
public final class OrdersTestClock extends Clock {

    private volatile Instant pinned;

    public void pin(Instant instant) {
        pinned = instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    public void release() {
        pinned = null;
    }

    /**
     * Microsecond-precise like the application clock ({@code ClockConfiguration}): PostgreSQL keeps microseconds, and on
     * Linux the system clock has nanoseconds, so an instant returned in memory would differ from the one read back.
     */
    @Override
    public Instant instant() {
        Instant now = pinned;
        return now != null ? now : Instant.now(Clock.systemUTC()).truncatedTo(ChronoUnit.MICROS);
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
        OrdersTestClock ordersTestClock() {
            return new OrdersTestClock();
        }
    }
}
