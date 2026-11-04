package com.xavier.backend.shared;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application time (D16). Inject {@link Clock}; never call {@code Instant.now()} without it.
 * Tests pin time with a {@code @TestConfiguration} that declares a {@code @Primary Clock} bean
 * (e.g. {@code Clock.fixed(...)}).
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
