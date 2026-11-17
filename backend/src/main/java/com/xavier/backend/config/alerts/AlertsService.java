package com.xavier.backend.config.alerts;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.AccessScope;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import com.xavier.backend.shared.tenancy.TenantScopes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

/**
 * The in-app outlet of D149: every {@link AlertSource} bean, computed at most every {@link #CACHE_FOR} so polling
 * never loads the database. A source that throws becomes one {@code ALERT_SOURCE_FAILED} warning (logged), never an
 * error. {@link #list} shows a {@code SYSTEM_MONITOR} holder the alerts of the stores it holds it in; tenant-wide
 * alerts (no store) only to a tenant-wide holder. The snapshot is per tenant (security rule 7, D186): computed in the
 * bound tenant and cached under its id, so one tenant's alerts never answer another's list. The health check sums the
 * ACTIVE tenants' snapshots, each computed in its own tenant.
 */
@Service
class AlertsService {

    static final Duration CACHE_FOR = Duration.ofSeconds(30);
    static final String SOURCE_FAILED = "ALERT_SOURCE_FAILED";

    private static final Logger log = LoggerFactory.getLogger(AlertsService.class);
    private static final Comparator<OperationalAlert> CRITICAL_FIRST = Comparator
            .comparing((OperationalAlert alert) -> alert.severity() == AlertSeverity.CRITICAL ? 0 : 1)
            .thenComparing(OperationalAlert::code)
            .thenComparing(alert -> alert.storeId() == null ? "" : alert.storeId().toString());

    /** Every active alert, unfiltered, and when they were collected. */
    record Snapshot(Instant generatedAt, List<OperationalAlert> alerts) {
    }

    private final List<AlertSource> sources;
    private final AccessPolicy accessPolicy;
    private final StoreScopes scopes;
    private final TenantScopes tenants;
    private final Clock clock;
    private final Map<UUID, Snapshot> cached = new ConcurrentHashMap<>();

    AlertsService(List<AlertSource> sources, AccessPolicy accessPolicy, StoreScopes scopes, TenantScopes tenants,
                  Clock clock) {
        this.sources = List.copyOf(sources);
        this.accessPolicy = accessPolicy;
        this.scopes = scopes;
        this.tenants = tenants;
        this.clock = clock;
    }

    /** The current actor's alerts, CRITICAL first. Needs {@code SYSTEM_MONITOR} somewhere. */
    AlertsView list() {
        AccessScope scope = accessPolicy.scopeOf(Permission.SYSTEM_MONITOR);
        Snapshot snapshot = current();
        return new AlertsView(snapshot.generatedAt(), snapshot.alerts().stream()
                .filter(alert -> scope.includes(alert.storeId()))
                .sorted(CRITICAL_FIRST)
                .toList());
    }

    /**
     * The bound tenant's cached snapshot, collected again when it is {@link #CACHE_FOR} old (or the clock went back).
     *
     * @throws IllegalStateException when no tenant is bound
     */
    Snapshot current() {
        UUID tenant = tenants.requireTenant();
        return cached.compute(tenant, (id, known) -> {
            Instant now = clock.instant();
            if (known != null && !now.isBefore(known.generatedAt())
                    && now.isBefore(known.generatedAt().plus(CACHE_FOR))) {
                return known;
            }
            // D166: computed tenant-wide (every store of the tenant), then filtered per actor by list()
            return new Snapshot(now, scopes.call(SystemScope.ALERTS, () -> collect(now)));
        });
    }

    /** The active alerts of every tenant in {@code tenantIds}, each counted from its own snapshot (the health check). */
    int activeIn(List<UUID> tenantIds) {
        return tenantIds.stream()
                .mapToInt(tenant -> tenants.call(tenant, SystemScope.ALERTS, () -> current().alerts().size())).sum();
    }

    private List<OperationalAlert> collect(Instant now) {
        List<OperationalAlert> alerts = new ArrayList<>();
        for (AlertSource source : sources) {
            String name = ClassUtils.getUserClass(source).getSimpleName();
            try {
                alerts.addAll(source.alerts());
            } catch (RuntimeException e) {
                log.warn("The alert source {} failed; reported as {}", name, SOURCE_FAILED, e);
                alerts.add(new OperationalAlert(SOURCE_FAILED, AlertSeverity.WARNING, null, 1, now,
                        "The alert source " + name + " failed: some alerts may be missing (see the log)."));
            }
        }
        return List.copyOf(alerts);
    }
}
