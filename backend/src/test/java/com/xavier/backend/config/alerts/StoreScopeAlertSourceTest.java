package com.xavier.backend.config.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.config.persistence.StoreScopeEnforcement;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.OperationalAlert;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.persistence.StoreScopeViolations.Kind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** P68/D170: an in-app alert while row-level security refused anything in the last 24 h, and while PERMISSIVE. */
class StoreScopeAlertSourceTest {

    final Clock clock = Clock.fixed(Instant.parse("2026-11-14T10:00:00Z"), ZoneOffset.UTC);
    final StoreScopeViolations violations = new StoreScopeViolations(new SimpleMeterRegistry(), clock);

    static org.springframework.mock.env.MockEnvironment mode(StoreScopeEnforcement enforcement) {
        return new org.springframework.mock.env.MockEnvironment()
                .withProperty(StoreScopeEnforcement.PROPERTY, enforcement.name());
    }

    @Test
    void noViolationAndEnforcingIsQuiet() {
        assertThat(new StoreScopeAlertSource(violations, mode(StoreScopeEnforcement.ENFORCE), clock).alerts()).isEmpty();
    }

    @Test
    void violationsOfTheLastDayAreOneCriticalAlertPerKindWithNoStore() {
        violations.record(Kind.WRITE_REFUSED, "listener:X.on");
        violations.record(Kind.WRITE_REFUSED, "listener:X.on");
        violations.record(Kind.SCOPE_MISSING, "system:ALERTS");

        assertThat(new StoreScopeAlertSource(violations, mode(StoreScopeEnforcement.ENFORCE), clock).alerts())
                .extracting(OperationalAlert::code, OperationalAlert::severity, OperationalAlert::storeId,
                        OperationalAlert::count)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("STORE_SCOPE_VIOLATION", AlertSeverity.CRITICAL, null, 2L),
                        org.assertj.core.groups.Tuple.tuple("STORE_SCOPE_VIOLATION", AlertSeverity.CRITICAL, null, 1L));
        assertThat(new StoreScopeAlertSource(violations, mode(StoreScopeEnforcement.ENFORCE), clock).alerts())
                .allSatisfy(alert -> assertThat(alert.summary()).doesNotContain("listener:X.on"));
    }

    @Test
    void permissiveModeIsACriticalAlertForAsLongAsItRuns() {
        assertThat(new StoreScopeAlertSource(violations, mode(StoreScopeEnforcement.PERMISSIVE), clock).alerts())
                .singleElement().satisfies(alert -> {
                    assertThat(alert.code()).isEqualTo("STORE_SCOPE_PERMISSIVE");
                    assertThat(alert.severity()).isEqualTo(AlertSeverity.CRITICAL);
                    assertThat(alert.storeId()).isNull();
                });
    }
}
