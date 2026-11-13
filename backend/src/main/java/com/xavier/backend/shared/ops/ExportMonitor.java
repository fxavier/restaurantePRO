package com.xavier.backend.shared.ops;

import com.xavier.backend.shared.exception.BusinessRuleException;
import com.xavier.backend.shared.exception.ConflictException;
import com.xavier.backend.shared.exception.NotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The {@code EXPORT_FAILED} alert of D150. Every CSV export runs through {@link #monitor}: it counts
 * {@code exports_total{kind, outcome=success|failure}} and remembers, in memory, the last failure per kind. A failure
 * in the last 24 h is an installation-wide WARNING; a restart forgets it (the counter's history is in Prometheus).
 * The six series (kind × outcome) are registered at 0 when the monitor is built.
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
    private static final List<String> OUTCOMES = List.of("success", "failure");

    private final MeterRegistry meters;
    private final Clock clock;
    private final Map<String, Instant> lastFailure = new ConcurrentHashMap<>();

    ExportMonitor(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;
        // every series exists at 0 from the start, so Prometheus's increase() sees the first failure after a restart
        for (String kind : KINDS) {
            for (String outcome : OUTCOMES) {
                counter(kind, outcome);
            }
        }
    }

    /**
     * Runs {@code export}, counts its outcome and, on failure, remembers when; the failure is rethrown unchanged. A
     * refusal (403 access denied or step-up, 404, 409, 422) is the caller's answer, not a failed export: it is
     * rethrown without being counted or alerted.
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
        } catch (AccessDeniedException | BusinessRuleException | NotFoundException | ConflictException
                 | ExportAborted refusal) {
            throw refusal;
        } catch (RuntimeException e) {
            lastFailure.merge(kind, clock.instant(), (a, b) -> a.isAfter(b) ? a : b);
            count(kind, "failure");
            throw e;
        }
        count(kind, "success");
        return result;
    }

    /**
     * The exception for a download the client cancelled (its writer failed): {@link #monitor} rethrows it without
     * counting or alerting, as it is neither a failure nor a success of the export.
     */
    public static RuntimeException aborted(IOException cause) {
        return new ExportAborted(cause);
    }

    /** A cancelled download; still an {@link UncheckedIOException} for the caller. */
    private static final class ExportAborted extends UncheckedIOException {

        private ExportAborted(IOException cause) {
            super(cause);
        }
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
        counter(kind, outcome).increment();
    }

    private Counter counter(String kind, String outcome) {
        return Counter.builder(METRIC)
                .description("CSV exports by kind and outcome")
                .tag("kind", kind)
                .tag("outcome", outcome)
                .register(meters);
    }
}
