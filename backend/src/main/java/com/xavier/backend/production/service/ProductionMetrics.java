package com.xavier.backend.production.service;

import com.xavier.backend.production.model.PrintJobStatus;
import com.xavier.backend.production.repository.PrintJobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import com.xavier.backend.production.repository.PrintJobRepository.StatusCount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code print_jobs{status}} (gauges read from one grouped count of the queue per scrape) and {@code production_unrouted_total}
 * (work with a destination or terminal that has no printer, kept UNROUTED: D89).
 */
@Service
public class ProductionMetrics {

    static final String UNROUTED = "production.unrouted";

    private static final Logger log = LoggerFactory.getLogger(ProductionMetrics.class);

    /** A scrape reads every status gauge within this, so they share one count. */
    static final Duration COUNTS_TTL = Duration.ofSeconds(1);

    private final Counter unrouted;
    private final PrintJobRepository jobs;
    private final Clock clock;
    private volatile Counts counts = new Counts(Map.of(), Instant.MIN);

    ProductionMetrics(MeterRegistry registry, PrintJobRepository jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
        for (PrintJobStatus status : PrintJobStatus.values()) {
            Gauge.builder("print_jobs", this, metrics -> metrics.jobsIn(status))
                    .tag("status", status.name()).description("Print jobs by status").register(registry);
        }
        this.unrouted = Counter.builder(UNROUTED).description("Production work with no printer to print on")
                .register(registry);
    }

    private synchronized long jobsIn(PrintJobStatus status) {
        Instant now = clock.instant();
        if (!now.isBefore(counts.countedAt().plus(COUNTS_TTL))) {
            counts = new Counts(jobs.countGroupedByStatus().stream()
                    .collect(Collectors.toMap(StatusCount::getStatus, StatusCount::getJobs)), now);
        }
        return counts.byStatus().getOrDefault(status, 0L);
    }

    private record Counts(Map<PrintJobStatus, Long> byStatus, Instant countedAt) {
    }

    /** Logged at WARN and counted: the work is kept UNROUTED, not printed until it is routed (D89 (1)). */
    void unrouted(UUID storeId, Object source, UUID sourceId, String where) {
        log.warn("Unrouted: no printer for {} in store {} ({} {})", where, storeId, source, sourceId);
        unrouted.increment();
    }
}
