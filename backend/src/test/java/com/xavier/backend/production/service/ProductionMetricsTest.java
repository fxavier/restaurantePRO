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
import com.xavier.backend.production.repository.StoreJobCount;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code print_jobs{status}}: one grouped count serves every status gauge of a scrape; and the per-store queue gauges
 * of D150 accumulate across tenants (R6: the advisor runs {@code refreshQueue} once per tenant, so a run never
 * replaces another tenant's stores).
 */
@StrictTenantScope
class ProductionMetricsTest {

    static final UUID STORE_A = UUID.fromString("0190b400-0000-7000-8000-0000000000aa");
    static final UUID STORE_B = UUID.fromString("0190b400-0000-7000-8000-0000000000bb");

    static final TenantScopes TENANTS = new TenantScopes(StoreScopeTestSupport.scopes(), Optional::empty);

    final PrintJobRepository jobs = mock(PrintJobRepository.class);
    final SettableClock clock = new SettableClock();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void aScrapeOfEveryStatusRunsOneGroupedCount() {
        when(jobs.countGroupedByStatus()).thenReturn(List.of(count(PrintJobStatus.SENT, 3),
                count(PrintJobStatus.FAILED, 1)));
        new ProductionMetrics(registry, jobs, StoreScopeTestSupport.scopes(), TENANTS, clock);

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
        new ProductionMetrics(registry, jobs, StoreScopeTestSupport.scopes(), TENANTS, clock);
        assertThat(scrape(PrintJobStatus.QUEUED)).isEqualTo(2);

        clock.advance(Duration.ofSeconds(2));

        assertThat(scrape(PrintJobStatus.QUEUED)).isEqualTo(5);
    }

    @Test
    void theQueueGaugesOfOneTenantSurviveTheRunOfTheNext() {
        ProductionMetrics metrics = new ProductionMetrics(registry, jobs, StoreScopeTestSupport.scopes(), TENANTS, clock);
        Instant oldest = clock.instant().minusSeconds(600);

        when(jobs.countUnresolvedSince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new StoreJobCount(STORE_A, 2L, 0L, oldest)), List.of());
        when(jobs.countWaitingOnNormalPrinters(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new StoreJobCount(STORE_A, 2L, 2L, oldest)), List.of());
        inTenant(TenantTestSupport.A, metrics::refreshQueue);
        when(jobs.countUnresolvedSince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(new StoreJobCount(STORE_B, 1L, 0L, oldest)));
        when(jobs.countWaitingOnNormalPrinters(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        inTenant(TenantTestSupport.B, metrics::refreshQueue);

        assertThat(queueGauge("print_jobs_unresolved", STORE_A)).as("tenant A after B's run").isEqualTo(2);
        assertThat(queueGauge("print_jobs_unresolved", STORE_B)).isEqualTo(1);
        assertThat(queueGauge("print_jobs_oldest_waiting_seconds", STORE_A)).isEqualTo(600);

        when(jobs.countUnresolvedSince(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        inTenant(TenantTestSupport.A, metrics::refreshQueue);

        assertThat(queueGauge("print_jobs_unresolved", STORE_A)).as("A resolved its jobs: back to 0").isZero();
        assertThat(queueGauge("print_jobs_unresolved", STORE_B)).as("B untouched by A's run").isEqualTo(1);
    }

    private static void inTenant(UUID tenant, java.util.function.Supplier<?> run) {
        TENANTS.callIn(new TenantContext(tenant, MembershipKind.SYSTEM, null, "test:metrics"), run);
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
