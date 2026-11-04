package com.xavier.backend.cash;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The application clock of the cash test context. It follows real time until a test pins it with {@link #set}
 * (e.g. to just after midnight, before the store's cutoff); {@link #release} goes back to real time. Only cash
 * tests use this context, and every cash test works on its own fresh store, so a pinned instant affects nobody else.
 */
public final class CashTestClock extends Clock {

    private volatile Instant pinned;

    public void set(Instant instant) {
        pinned = instant;
    }

    public void release() {
        pinned = null;
    }

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
        CashTestClock cashTestClock() {
            return new CashTestClock();
        }
    }
}
