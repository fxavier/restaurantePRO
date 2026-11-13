package com.xavier.backend.config.alerts;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.AccessScope;
import com.xavier.backend.access.Permission;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

/**
 * The in-app outlet of D149: every {@link AlertSource} bean, computed at most every {@link #CACHE_FOR} so polling
 * never loads the database. A source that throws becomes one {@code ALERT_SOURCE_FAILED} warning (logged), never an
 * error. {@link #list} shows a {@code SYSTEM_MONITOR} holder the alerts of the stores it holds it in; installation-wide
 * alerts (no store) only to an installation-wide holder.
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
    private final Clock clock;
    private Snapshot cached;

    AlertsService(List<AlertSource> sources, AccessPolicy accessPolicy, Clock clock) {
        this.sources = List.copyOf(sources);
        this.accessPolicy = accessPolicy;
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

    /** The cached snapshot, collected again when it is {@link #CACHE_FOR} old (or the clock went back). */
    synchronized Snapshot current() {
        Instant now = clock.instant();
        if (cached == null || now.isBefore(cached.generatedAt())
                || !now.isBefore(cached.generatedAt().plus(CACHE_FOR))) {
            cached = new Snapshot(now, collect(now));
        }
        return cached;
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
