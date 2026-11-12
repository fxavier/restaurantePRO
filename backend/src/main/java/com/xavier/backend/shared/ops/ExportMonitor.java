package com.xavier.backend.shared.ops;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The {@code EXPORT_FAILED} alert of D150. Every CSV export runs through {@link #monitor}: it counts
 * {@code exports_total{kind, outcome=success|failure}} and remembers, in memory, the last failure per kind. A failure
 * in the last 24 h is an installation-wide WARNING; a restart forgets it (the counter's history is in Prometheus).
 * Kinds are a closed set so the {@code kind} label stays bounded (§12).
 */
@Component
public class ExportMonitor implements AlertSource {

    public static final String REPORT_CSV = "report_csv";
    public static final String AUDIT_CSV = "audit_csv";
    public static final String INVENTORY_CSV = "inventory_csv";

    static final String METRIC = "exports";
    static final String ALERT_CODE = "EXPORT_FAILED";
    static final Duration WINDOW = Duration.ofHours(24);
    private static final Set<String> KINDS = Set.of(REPORT_CSV, AUDIT_CSV, INVENTORY_CSV);

    private final MeterRegistry meters;
    private final Clock clock;
    private final Map<String, Instant> lastFailure = new ConcurrentHashMap<>();

    ExportMonitor(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * Runs {@code export}, counts its outcome and, on failure, remembers when; the failure is rethrown unchanged.
     *
     * @param kind one of {@link #REPORT_CSV}, {@link #AUDIT_CSV}, {@link #INVENTORY_CSV}
     * @throws IllegalArgumentException for another kind, before the export runs
     */
    public <T> T monitor(String kind, Supplier<T> export) {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unknown export kind: " + kind);
        }
        T result;
        try {
            result = export.get();
        } catch (RuntimeException e) {
            lastFailure.put(kind, clock.instant());
            count(kind, "failure");
            throw e;
        }
        count(kind, "success");
        return result;
    }

    /** One installation-wide WARNING per kind whose last failure is at most 24 h before {@code now}, in no order. */
    public List<OperationalAlert> recentFailures(Instant now) {
        Instant from = now.minus(WINDOW);
        return lastFailure.entrySet().stream()
                .filter(failure -> !failure.getValue().isBefore(from))
                .map(failure -> new OperationalAlert(ALERT_CODE, AlertSeverity.WARNING, null, 1, failure.getValue(),
                        "The last " + failure.getKey() + " export failed."))
                .toList();
    }

    @Override
    public List<OperationalAlert> alerts() {
        return recentFailures(clock.instant());
    }

    private void count(String kind, String outcome) {
        Counter.builder(METRIC)
                .description("CSV exports by kind and outcome")
                .tag("kind", kind)
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }
}
