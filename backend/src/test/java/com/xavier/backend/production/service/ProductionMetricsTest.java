package com.xavier.backend.production.service;

import com.xavier.backend.access.StoreScopeTestSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xavier.backend.production.model.PrintJobStatus;
import com.xavier.backend.production.repository.PrintJobRepository;
import com.xavier.backend.production.repository.PrintJobRepository.StatusCount;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code print_jobs{status}}: one grouped count serves every status gauge of a scrape. */
class ProductionMetricsTest {

    final PrintJobRepository jobs = mock(PrintJobRepository.class);
    final SettableClock clock = new SettableClock();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void aScrapeOfEveryStatusRunsOneGroupedCount() {
        when(jobs.countGroupedByStatus()).thenReturn(List.of(count(PrintJobStatus.SENT, 3),
                count(PrintJobStatus.FAILED, 1)));
        new ProductionMetrics(registry, jobs, StoreScopeTestSupport.scopes(), clock);

        assertThat(scrape(PrintJobStatus.SENT)).isEqualTo(3);
        assertThat(scrape(PrintJobStatus.FAILED)).isEqualTo(1);
        for (PrintJobStatus status : PrintJobStatus.values()) {
            scrape(status);
        }
        assertThat(scrape(PrintJobStatus.QUEUED)).isZero();

        verify(jobs, times(1)).countGroupedByStatus();
    }

    @Test
    void theNextScrapeCountsAgain() {
        when(jobs.countGroupedByStatus()).thenReturn(List.of(count(PrintJobStatus.QUEUED, 2)),
                List.of(count(PrintJobStatus.QUEUED, 5)));
        new ProductionMetrics(registry, jobs, StoreScopeTestSupport.scopes(), clock);
        assertThat(scrape(PrintJobStatus.QUEUED)).isEqualTo(2);

        clock.advance(Duration.ofSeconds(2));

        assertThat(scrape(PrintJobStatus.QUEUED)).isEqualTo(5);
    }

    private double scrape(PrintJobStatus status) {
        return registry.get("print_jobs").tag("status", status.name()).gauge().value();
    }

    private static StatusCount count(PrintJobStatus status, long jobs) {
        return new StatusCount() {
            @Override
            public PrintJobStatus getStatus() {
                return status;
            }

            @Override
            public long getJobs() {
                return jobs;
            }
        };
    }

    static final class SettableClock extends Clock {

        private Instant now = Instant.parse("2026-11-05T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
