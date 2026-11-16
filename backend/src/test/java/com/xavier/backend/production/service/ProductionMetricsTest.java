package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xavier.backend.access.StoreScopeTestSupport;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.production.model.PrintJobStatus;
import com.xavier.backend.production.repository.PrintJobRepository;
import com.xavier.backend.production.repository.PrintJobRepository.StatusCount;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code print_jobs{status}}: a scrape reads no database (a scrape thread has no tenant, D185); it shows each tenant's
 * last refresh (one grouped count per tenant, run by the advisor once per tenant), summed, never a tenant label.
 */
class ProductionMetricsTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    final PrintJobRepository jobs = mock(PrintJobRepository.class);
    final SettableClock clock = new SettableClock();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final StoreScopes stores = StoreScopeTestSupport.scopes();
    final TenantScopes tenants = new TenantScopes(stores, java.util.Optional::empty);

    @Test
    void aScrapeShowsEveryTenantsLastRefreshAndReadsNoDatabase() {
        when(jobs.countGroupedByStatus()).thenReturn(List.of(count(PrintJobStatus.SENT, 3),
                count(PrintJobStatus.FAILED, 1)), List.of(count(PrintJobStatus.SENT, 2)));
        ProductionMetrics metrics = new ProductionMetrics(registry, jobs, stores, tenants, clock);

        TenantTestSupport.inTenant(tenants, A, metrics::refreshStatusCounts);
        TenantTestSupport.inTenant(tenants, B, metrics::refreshStatusCounts);
        for (PrintJobStatus status : PrintJobStatus.values()) {
            scrape(status);
        }

        assertThat(scrape(PrintJobStatus.SENT)).isEqualTo(5);
        assertThat(scrape(PrintJobStatus.FAILED)).isEqualTo(1);
        assertThat(scrape(PrintJobStatus.QUEUED)).isZero();
        verify(jobs, times(2)).countGroupedByStatus();
    }

    @Test
    void aTenantsNextRefreshReplacesOnlyItsOwnCounts() {
        when(jobs.countGroupedByStatus()).thenReturn(List.of(count(PrintJobStatus.QUEUED, 2)),
                List.of(count(PrintJobStatus.QUEUED, 4)), List.of(count(PrintJobStatus.QUEUED, 5)));
        ProductionMetrics metrics = new ProductionMetrics(registry, jobs, stores, tenants, clock);
        TenantTestSupport.inTenant(tenants, A, metrics::refreshStatusCounts);
        TenantTestSupport.inTenant(tenants, B, metrics::refreshStatusCounts);

        TenantTestSupport.inTenant(tenants, A, metrics::refreshStatusCounts);

        assertThat(scrape(PrintJobStatus.QUEUED)).isEqualTo(9);
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
