package com.xavier.backend.shared;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application time (D16). Inject {@link Clock}; never call {@code Instant.now()} without it.
 * Tests pin time with a {@code @TestConfiguration} that declares a {@code @Primary Clock} bean
 * (e.g. {@code Clock.fixed(...)}).
 *
 * <p>The clock ticks in whole microseconds: PostgreSQL {@code timestamptz} stores microseconds, so an instant with
 * nanoseconds would differ from itself after a round trip (an in-memory value compared with the stored one, a
 * compare-and-set on a timestamp). A test clock should be microsecond-precise for the same reason.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
