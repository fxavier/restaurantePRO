package com.xavier.backend.config.alerts;

import com.xavier.backend.access.StoreScopeTestSupport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.xavier.backend.config.alerts.AlertsService.Snapshot;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * D149, D161: the aggregated alerts are computed at most every 30 s, so polling the list (or the health check) never
 * loads the database; a source that throws becomes one {@code ALERT_SOURCE_FAILED} warning instead of an error.
 * Security rule 7 (D186): the snapshot is per tenant, computed in that tenant, so one tenant's cached alerts never
 * answer another's list.
 */
class AlertsCacheTest {

    static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    final TestClock clock = new TestClock(NOW);
    final AtomicInteger calls = new AtomicInteger();
    final OperationalAlert alert = new OperationalAlert("PAYMENTS_IN_DOUBT", AlertSeverity.CRITICAL,
            UUID.randomUUID(), 1, NOW, "In doubt.");
    final AlertSource counting = () -> {
        calls.incrementAndGet();
        return List.of(alert);
    };

    final TenantScopes tenants = new TenantScopes(StoreScopeTestSupport.scopes(), Optional::empty);

    private AlertsService service(AlertSource... sources) {
        return new AlertsService(List.of(sources), null, StoreScopeTestSupport.scopes(), tenants, clock);
    }

    private Snapshot in(UUID tenant, AlertsService service) {
        return TenantTestSupport.inTenant(tenants, tenant, service::current);
    }

    @Test
    void eachTenantGetsItsOwnSnapshotComputedInItself() {
        AlertSource perTenant = () -> List.of(new OperationalAlert("OF_TENANT", AlertSeverity.WARNING, null, 1, NOW,
                "of " + tenants.requireTenant()));
        AlertsService service = service(perTenant);

        Snapshot a = in(TenantTestSupport.A, service);
        Snapshot b = in(TenantTestSupport.B, service);

        assertThat(a.alerts()).extracting(OperationalAlert::summary).containsExactly("of " + TenantTestSupport.A);
        assertThat(b.alerts()).extracting(OperationalAlert::summary).containsExactly("of " + TenantTestSupport.B);
    }

    @Test
    void theSnapshotNeedsATenant() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service(counting).current())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theSourcesAreAskedAgainOnlyAfter30Seconds() {
        AlertsService service = service(counting);

        Snapshot first = in(TenantTestSupport.A, service);
        clock.advance(Duration.ofMillis(29_999));
        in(TenantTestSupport.A, service);
        assertThat(calls).hasValue(1);

        clock.advance(Duration.ofMillis(1));
        Snapshot refreshed = in(TenantTestSupport.A, service);

        assertThat(calls).hasValue(2);
        assertThat(first.generatedAt()).isEqualTo(NOW);
        assertThat(refreshed.generatedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(refreshed.alerts()).containsExactly(alert);
    }

    @Test
    void aClockThatWentBackCollectsAgain() {
        AlertsService service = service(counting);
        in(TenantTestSupport.A, service);

        clock.advance(Duration.ofSeconds(-1));
        in(TenantTestSupport.A, service);

        assertThat(calls).as("a snapshot from the future is not trusted").hasValue(2);
    }

    @Test
    void aFailingSourceBecomesOneWarningAndTheOthersStillReport() {
        AlertSource failing = () -> {
            throw new IllegalStateException("database down");
        };
        AlertsService service = service(failing, counting);

        assertThat(in(TenantTestSupport.A, service).alerts())
                .extracting(OperationalAlert::code, OperationalAlert::severity, OperationalAlert::storeId,
                        OperationalAlert::count, OperationalAlert::since)
                .containsExactlyInAnyOrder(
                        tuple("ALERT_SOURCE_FAILED", AlertSeverity.WARNING, null, 1L, NOW),
                        tuple("PAYMENTS_IN_DOUBT", AlertSeverity.CRITICAL, alert.storeId(), 1L, NOW));
        assertThat(in(TenantTestSupport.A, service).alerts().stream()
                .filter(a -> a.code().equals("ALERT_SOURCE_FAILED")).findFirst().orElseThrow().summary())
                .doesNotContain("database down");
    }
}
