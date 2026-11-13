package com.xavier.backend.shared.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.xavier.backend.shared.exception.BusinessRuleException;
import com.xavier.backend.shared.exception.ConflictException;
import com.xavier.backend.shared.exception.NotFoundException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.AccessDeniedException;

/** D150 EXPORT_FAILED: counts every export by kind and outcome, and alerts on a failure in the last 24 h. */
class ExportMonitorTest {

    static final Instant T0 = Instant.parse("2026-11-12T10:00:00Z");

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final AtomicReference<Instant> now = new AtomicReference<>(T0);
    final ExportMonitor monitor = new ExportMonitor(meters, new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    });

    @Test
    void aSuccessfulExportReturnsItsResultAndCountsASuccess() {
        String csv = monitor.monitor(ExportMonitor.REPORT_CSV, () -> "a;b");

        assertThat(csv).isEqualTo("a;b");
        assertThat(count("report_csv", "success")).isEqualTo(1.0);
        assertThat(meters.find("exports").tag("outcome", "failure").counter()).isNull();
        assertThat(monitor.alerts()).isEmpty();
    }

    @Test
    void aFailedExportIsRethrownCountedAndRaisesAnInstallationWideWarning() {
        IllegalStateException failure = new IllegalStateException("disk full");

        assertThatThrownBy(() -> monitor.monitor(ExportMonitor.AUDIT_CSV, () -> {
            throw failure;
        })).isSameAs(failure);

        assertThat(count("audit_csv", "failure")).isEqualTo(1.0);
        assertThat(meters.find("exports").tag("outcome", "success").counter()).isNull();
        assertThat(monitor.alerts()).containsExactly(new OperationalAlert("EXPORT_FAILED", AlertSeverity.WARNING,
                null, 1, T0, "The last audit_csv export failed."));
    }

    @Test
    void aFailureStaysAnAlertFor24HoursOnly() {
        fail(ExportMonitor.INVENTORY_CSV);

        assertThat(monitor.recentFailures(T0.plus(Duration.ofHours(24)))).hasSize(1);
        assertThat(monitor.recentFailures(T0.plus(Duration.ofHours(24)).plusNanos(1_000))).isEmpty();
    }

    @Test
    void theLastFailurePerKindIsRemembered() {
        fail(ExportMonitor.REPORT_CSV);
        now.set(T0.plus(Duration.ofHours(2)));
        fail(ExportMonitor.REPORT_CSV);
        fail(ExportMonitor.AUDIT_CSV);
        monitor.monitor(ExportMonitor.REPORT_CSV, () -> "ok");

        assertThat(monitor.recentFailures(T0.plus(Duration.ofHours(25))))
                .extracting(OperationalAlert::summary, OperationalAlert::since)
                .containsExactlyInAnyOrder(
                        tuple("The last audit_csv export failed.",
                                T0.plus(Duration.ofHours(2))),
                        tuple("The last report_csv export failed.",
                                T0.plus(Duration.ofHours(2))));
        assertThat(count("report_csv", "failure")).isEqualTo(2.0);
    }

    /** A refusal (403, 404, 409, 422) is the caller's answer, not a failed export: no count, no alert. */
    @ParameterizedTest
    @MethodSource("refusals")
    void aRefusalIsRethrownWithoutCountingOrAlerting(RuntimeException refusal) {
        assertThatThrownBy(() -> monitor.monitor(ExportMonitor.REPORT_CSV, () -> {
            throw refusal;
        })).isSameAs(refusal);

        assertThat(meters.find("exports").counters()).isEmpty();
        assertThat(monitor.alerts()).isEmpty();
    }

    static Stream<RuntimeException> refusals() {
        return Stream.of(new AccessDeniedException("no"), new BusinessRuleException("RANGE_TOO_LARGE", "too large"),
                new NotFoundException("missing"), new ConflictException("changed"));
    }

    /** Two exports racing: the failure that happened later is the one remembered, whatever finishes last. */
    @Test
    void anEarlierFailureRecordedLateDoesNotHideALaterOne() {
        now.set(T0.plus(Duration.ofHours(2)));
        fail(ExportMonitor.AUDIT_CSV);
        now.set(T0);
        fail(ExportMonitor.AUDIT_CSV);

        assertThat(monitor.recentFailures(T0.plus(Duration.ofHours(25))))
                .extracting(OperationalAlert::since).containsExactly(T0.plus(Duration.ofHours(2)));
    }

    @Test
    void anUnknownKindIsRefusedBeforeTheExportRuns() {
        AtomicBoolean ran = new AtomicBoolean();

        assertThatThrownBy(() -> monitor.monitor("orders_of_customer_42", () -> {
            ran.set(true);
            return "x";
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(ran).isFalse();
        assertThat(meters.find("exports").counters()).isEmpty();
    }

    private void fail(String kind) {
        assertThatThrownBy(() -> monitor.monitor(kind, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
    }

    private double count(String kind, String outcome) {
        Counter counter = meters.get("exports").tag("kind", kind).tag("outcome", outcome).counter();
        return counter.count();
    }
}
