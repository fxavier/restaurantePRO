package com.xavier.backend.production.service;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.production.model.PrintJobStatus;
import com.xavier.backend.production.repository.PrintJobRepository;
import com.xavier.backend.production.repository.PrintJobRepository.StatusCount;
import com.xavier.backend.production.repository.StoreJobCount;
import com.xavier.backend.shared.tenancy.TenantScopes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
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
 * once seen keeps reporting, at 0 when all is well. The only label is the store (§12: never a job id; the tenant
 * is never a label, R6). A refresh runs once per tenant (the advisor, or the alert request of one tenant), so the
 * queue state is kept per tenant and every gauge shows all of them: one tenant's run never replaces another's.
 */
@Service
public class ProductionMetrics {

    static final String UNROUTED = "production.unrouted";
    /** D150: a FAILED or UNKNOWN job needs a person within this window. */
    static final Duration ATTENTION_WINDOW = Duration.ofHours(24);
    /** D150: a job waiting longer than this on a NORMAL printer means the queue is stalled. */
    static final Duration STALLED_AFTER = Duration.ofMinutes(5);

    /** A tenant not refreshed this long (suspended, closed: the advisor skips it) no longer counts in the sums. */
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(ProductionMetrics.class);

    private final Counter unrouted;
    private final PrintJobRepository jobs;
    private final Clock clock;
    private final TenantScopes tenants;
    private final MultiGauge unresolvedGauge;
    private final MultiGauge oldestWaitingGauge;
    /** Per tenant, the queue it showed at its last refresh (guarded by {@code this}). */
    private final Map<UUID, TenantQueue> queues = new HashMap<>();
    /** Per tenant, its print jobs by status at its last refresh: what {@code print_jobs{status}} sums (R6). */
    private final Map<UUID, StatusCounts> statusCounts = new ConcurrentHashMap<>();

    ProductionMetrics(MeterRegistry registry, PrintJobRepository jobs, TenantScopes tenants, Clock clock) {
        this.jobs = jobs;
        this.tenants = tenants;
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

    /**
     * The print queue's health per store of the bound tenant now (two indexed counts), also published as the gauges
     * together with every other tenant's last refresh.
     */
    @SystemScoped(SystemScope.METRICS)
    @Scheduled(fixedDelayString = "${restaurantpro.production.queue-gauges-interval:PT30S}")
    public synchronized QueueHealth refreshQueue() {
        Instant now = clock.instant();
        QueueHealth health = new QueueHealth(now, jobs.countUnresolvedSince(now.minus(ATTENTION_WINDOW)),
                jobs.countWaitingOnNormalPrinters(now.minus(STALLED_AFTER)));
        queues.computeIfAbsent(tenants.requireTenant(), tenant -> new TenantQueue()).update(health, now);
        unresolvedGauge.register(rows(TenantQueue::unresolved), true);
        oldestWaitingGauge.register(rows(TenantQueue::oldestWaitingSeconds), true);
        return health;
    }

    /** Per store: the jobs needing attention, and the jobs waiting on NORMAL printers (with how many are late). */
    public record QueueHealth(Instant at, List<StoreJobCount> unresolved, List<StoreJobCount> waiting) {
    }

    private List<MultiGauge.Row<?>> rows(Function<TenantQueue, Map<UUID, Long>> value) {
        List<MultiGauge.Row<?>> rows = new ArrayList<>();
        for (TenantQueue queue : queues.values()) {
            Map<UUID, Long> byStore = value.apply(queue);
            for (UUID store : queue.stores) {
                rows.add(MultiGauge.Row.of(Tags.of("store", store.toString()), byStore.getOrDefault(store, 0L)));
            }
        }
        return rows;
    }

    /** One tenant's stores (once seen, always reported) and what each showed at its last refresh. */
    private static final class TenantQueue {

        private final Set<UUID> stores = new HashSet<>();
        private Map<UUID, Long> unresolved = Map.of();
        private Map<UUID, Long> oldestWaitingSeconds = Map.of();

        void update(QueueHealth health, Instant now) {
            health.unresolved().forEach(count -> stores.add(count.storeId()));
            health.waiting().forEach(count -> stores.add(count.storeId()));
            unresolved = health.unresolved().stream()
                    .collect(Collectors.toMap(StoreJobCount::storeId, StoreJobCount::jobs));
            oldestWaitingSeconds = health.waiting().stream().collect(Collectors.toMap(StoreJobCount::storeId,
                    count -> Duration.between(count.oldestCreatedAt(), now).toSeconds()));
        }

        Map<UUID, Long> unresolved() {
            return unresolved;
        }

        Map<UUID, Long> oldestWaitingSeconds() {
            return oldestWaitingSeconds;
        }
    }

    /**
     * The bound tenant's print jobs by status (one grouped count), kept for {@code print_jobs{status}}: the advisor
     * runs it once per tenant (D185), so a scrape, which has no tenant, never reads the database.
     */
    @SystemScoped(SystemScope.METRICS)
    @Scheduled(fixedDelayString = "${restaurantpro.production.queue-gauges-interval:PT30S}")
    public void refreshStatusCounts() {
        statusCounts.put(tenants.requireTenant(), new StatusCounts(jobs.countGroupedByStatus().stream()
                .collect(Collectors.toMap(StatusCount::getStatus, StatusCount::getJobs)), clock.instant()));
    }

    /** Every tenant's last count, summed (the tenant is never a label, P74). */
    private long jobsIn(PrintJobStatus status) {
        Instant oldestFresh = clock.instant().minus(STALE_AFTER);
        statusCounts.values().removeIf(counts -> counts.at().isBefore(oldestFresh));
        return statusCounts.values().stream().mapToLong(counts -> counts.byStatus().getOrDefault(status, 0L)).sum();
    }

    private record StatusCounts(Map<PrintJobStatus, Long> byStatus, Instant at) {
    }

    /** Logged at WARN and counted: the work is kept UNROUTED, not printed until it is routed (D89 (1)). */
    void unrouted(UUID storeId, Object source, UUID sourceId, String where) {
        log.warn("Unrouted: no printer for {} in store {} ({} {})", where, storeId, source, sourceId);
        unrouted.increment();
    }
}
