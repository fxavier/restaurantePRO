package com.xavier.backend.fiscal;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The system clock with a sub-microsecond part on every reading, as on Linux (JDK 15+), where PostgreSQL then
 * rounds to microseconds. macOS clocks tick in microseconds, so without this a precision bug would hide there.
 */
public final class NanoClock extends Clock {

    @Override
    public Instant instant() {
        return Clock.systemUTC().instant().truncatedTo(ChronoUnit.MICROS).plusNanos(789);
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
        NanoClock nanoClock() {
            return new NanoClock();
        }
    }
}
