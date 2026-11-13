package com.xavier.backend.production.service;

import com.xavier.backend.production.model.PrintJobStatus;
import com.xavier.backend.production.repository.PrintJobRepository;
import com.xavier.backend.production.repository.PrintJobRepository.StatusCount;
import com.xavier.backend.production.repository.StoreJobCount;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * {@code print_jobs{status}} (gauges read from one grouped count of the queue per scrape),
 * {@code production_unrouted_total} (work with a destination or terminal that has no printer, kept UNROUTED: D89),
 * and the print-queue alert gauges of D150, {@code print_jobs_unresolved{store}} and
 * {@code print_jobs_oldest_waiting_seconds{store}}, refreshed every 30 s and whenever the alerts are read. A store
 * once seen keeps reporting, at 0 when all is well. The only label is the store (§12: never a job id).
 */
@Service
public class ProductionMetrics {

    static final String UNROUTED = "production.unrouted";
    /** D150: a FAILED or UNKNOWN job needs a person within this window. */
    static final Duration ATTENTION_WINDOW = Duration.ofHours(24);
    /** D150: a job waiting longer than this on a NORMAL printer means the queue is stalled. */
    static final Duration STALLED_AFTER = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(ProductionMetrics.class);

    /** A scrape reads every status gauge within this, so they share one count. */
    static final Duration COUNTS_TTL = Duration.ofSeconds(1);

    private final Counter unrouted;
    private final PrintJobRepository jobs;
    private final Clock clock;
    private final MultiGauge unresolvedGauge;
    private final MultiGauge oldestWaitingGauge;
    private final Set<UUID> stores = ConcurrentHashMap.newKeySet();
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
        this.unresolvedGauge = MultiGauge.builder("print_jobs_unresolved")
                .description("FAILED or UNKNOWN print jobs of the last 24 h that nobody resolved").register(registry);
        this.oldestWaitingGauge = MultiGauge.builder("print_jobs_oldest_waiting_seconds")
                .description("Age of the oldest QUEUED or RETRY_WAIT print job on a NORMAL printer")
                .baseUnit("seconds").register(registry);
    }

    /** The print queue's health per store now (two indexed counts), also published as the gauges. */
    @Scheduled(fixedDelayString = "${restaurantpro.production.queue-gauges-interval:PT30S}")
    public synchronized QueueHealth refreshQueue() {
        Instant now = clock.instant();
        QueueHealth health = new QueueHealth(now, jobs.countUnresolvedSince(now.minus(ATTENTION_WINDOW)),
                jobs.countWaitingOnNormalPrinters(now.minus(STALLED_AFTER)));
        health.unresolved().forEach(count -> stores.add(count.storeId()));
        health.waiting().forEach(count -> stores.add(count.storeId()));
        unresolvedGauge.register(rows(health.unresolved(), StoreJobCount::jobs), true);
        oldestWaitingGauge.register(rows(health.waiting(),
                count -> Duration.between(count.oldestCreatedAt(), now).toSeconds()), true);
        return health;
    }

    /** Per store: the jobs needing attention, and the jobs waiting on NORMAL printers (with how many are late). */
    public record QueueHealth(Instant at, List<StoreJobCount> unresolved, List<StoreJobCount> waiting) {
    }

    private List<MultiGauge.Row<?>> rows(List<StoreJobCount> counts, ToLongFunction<StoreJobCount> value) {
        Map<UUID, Long> byStore = counts.stream()
                .collect(Collectors.toMap(StoreJobCount::storeId, value::applyAsLong));
        return stores.stream().<MultiGauge.Row<?>>map(store -> MultiGauge.Row.of(Tags.of("store", store.toString()),
                byStore.getOrDefault(store, 0L))).toList();
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
