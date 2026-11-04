package com.xavier.backend.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ClockConfigurationTest {

    @Autowired
    Clock clock;

    @Autowired
    JdbcClient jdbc;

    @Test
    void applicationTimeComesFromAnInjectableUtcClock() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    /**
     * PostgreSQL keeps microseconds: an instant with nanoseconds would come back different from a
     * {@code timestamptz} round trip (the S12 review finding). The application clock ticks in whole microseconds.
     */
    @Test
    void applicationTimeIsMicrosecondPreciseSoItSurvivesAPostgresRoundTrip() {
        assertThat(clock).isEqualTo(Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000)));
        for (int i = 0; i < 100; i++) {
            Instant now = clock.instant();
            assertThat(now.getNano() % 1_000).isZero();
            Instant stored = jdbc.sql("SELECT CAST(:at AS timestamptz)").param("at", Timestamp.from(now))
                    .query(Timestamp.class).single().toInstant();
            assertThat(stored).isEqualTo(now);
        }
    }
}
