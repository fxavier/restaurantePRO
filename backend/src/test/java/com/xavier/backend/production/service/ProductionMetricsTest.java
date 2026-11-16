package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xavier.backend.access.StoreScopeTestSupport;
import com.xavier.backend.production.model.PrintJobStatus;
import com.xavier.backend.production.repository.PrintJobRepository;
import com.xavier.backend.production.repository.PrintJobRepository.StatusCount;
import com.xavier.backend.production.repository.StoreJobCount;
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
    final TenantScopes tenants = new TenantScopes(StoreScopeTestSupport.scopes(), java.util.Optional::empty);

    @Test
    void aScrapeShowsEveryTenantsLastRefreshAndReadsNoDatabase() {
        when(jobs.countGroupedByStatus()).thenReturn(List.of(count(PrintJobStatus.SENT, 3),
                count(PrintJobStatus.FAILED, 1)), List.of(count(PrintJobStatus.SENT, 2)));
        ProductionMetrics metrics = new ProductionMetrics(registry, jobs, tenants, clock);

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
        ProductionMetrics metrics = new ProductionMetrics(registry, jobs, tenants, clock);
        TenantTestSupport.inTenant(tenants, A, metrics::refreshStatusCounts);
        TenantTestSupport.inTenant(tenants, B, metrics::refreshStatusCounts);

        TenantTestSupport.inTenant(tenants, A, metrics::refreshStatusCounts);

        assertThat(scrape(PrintJobStatus.QUEUED)).isEqualTo(9);
    }

    @Test
    void aTenantThatStoppedRefreshingIsEvictedFromTheSummedCounts() {
        when(jobs.countGroupedByStatus()).thenReturn(List.of(count(PrintJobStatus.QUEUED, 2)),
                List.of(count(PrintJobStatus.QUEUED, 4)), List.of(count(PrintJobStatus.QUEUED, 5)));
        ProductionMetrics metrics = new ProductionMetrics(registry, jobs, tenants, clock);
        TenantTestSupport.inTenant(tenants, A, metrics::refreshStatusCounts);
        TenantTestSupport.inTenant(tenants, B, metrics::refreshStatusCounts);
        assertThat(scrape(PrintJobStatus.QUEUED)).isEqualTo(6);

        // B is suspended: the advisor no longer runs it, A keeps refreshing
        clock.advance(ProductionMetrics.STALE_AFTER.plusSeconds(1));
        TenantTestSupport.inTenant(tenants, A, metrics::refreshStatusCounts);

        assertThat(scrape(PrintJobStatus.QUEUED)).as("only A's fresh count").isEqualTo(5);
    }

    @Test
    void theQueueGaugesOfOneTenantSurviveTheRunOfTheNext() {
        ProductionMetrics metrics = new ProductionMetrics(registry, jobs, tenants, clock);
        UUID storeA = UUID.fromString("0190b400-0000-7000-8000-0000000000aa");
        UUID storeB = UUID.fromString("0190b400-0000-7000-8000-0000000000bb");
        Instant oldest = clock.instant().minusSeconds(600);

        when(jobs.countUnresolvedSince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new StoreJobCount(storeA, 2L, 0L, oldest)), List.of());
        when(jobs.countWaitingOnNormalPrinters(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new StoreJobCount(storeA, 2L, 2L, oldest)), List.of());
        TenantTestSupport.inTenant(tenants, A, metrics::refreshQueue);
        when(jobs.countUnresolvedSince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new StoreJobCount(storeB, 1L, 0L, oldest)));
        when(jobs.countWaitingOnNormalPrinters(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        TenantTestSupport.inTenant(tenants, B, metrics::refreshQueue);

        assertThat(queueGauge("print_jobs_unresolved", storeA)).as("tenant A after B's run").isEqualTo(2);
        assertThat(queueGauge("print_jobs_unresolved", storeB)).isEqualTo(1);
        assertThat(queueGauge("print_jobs_oldest_waiting_seconds", storeA)).isEqualTo(600);

        when(jobs.countUnresolvedSince(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        TenantTestSupport.inTenant(tenants, A, metrics::refreshQueue);

        assertThat(queueGauge("print_jobs_unresolved", storeA)).as("A resolved its jobs: back to 0").isZero();
        assertThat(queueGauge("print_jobs_unresolved", storeB)).as("B untouched by A's run").isEqualTo(1);
    }

    private double queueGauge(String name, UUID store) {
        return registry.get(name).tag("store", store.toString()).gauge().value();
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
