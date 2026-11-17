package com.xavier.backend.config.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.cash.service.CashAlertsTestSupport;
import com.xavier.backend.settlement.SettlementTestClock;
import com.xavier.backend.settlement.service.InDoubtTestSupport;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.ExportMonitor;
import com.xavier.backend.shared.ops.OperationalAlert;
import com.xavier.backend.shared.tenancy.MembershipKind;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Security rule 7 (D186) for the alert outlet: one tenant's monitor never sees another tenant's alerts. The sources
 * are the real ones; tenants A and B each get a cash discrepancy, an overdue in-doubt attempt and a failed export.
 */
@AlertsIntegrationTest
class AlertsTenantIsolationTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;

    @Autowired
    ApplicationContext context;
    @Autowired
    AlertsService alerts;
    @Autowired
    TenantScopes tenants;
    @Autowired
    StoreScopes scopes;
    @Autowired
    ExportMonitor exports;
    @Autowired
    SettlementTestClock clock;

    final List<UUID> storesOfA = new ArrayList<>();
    final List<UUID> storesOfB = new ArrayList<>();

    @BeforeEach
    void alertsInBothTenants() {
        TenantTestSupport.ensureTenants();
        clock.advance(AlertsService.CACHE_FOR.plusSeconds(1)); // no snapshot of an earlier test answers
        storesOfA.add(CashAlertsTestSupport.discrepancyIn(context, A, clock.instant()));
        storesOfB.add(CashAlertsTestSupport.discrepancyIn(context, B, clock.instant()));
        storesOfA.add(InDoubtTestSupport.overdueAttemptIn(context, A, clock.instant()));
        storesOfB.add(InDoubtTestSupport.overdueAttemptIn(context, B, clock.instant()));
        failAnExportIn(B);
    }

    @AfterEach
    void restore() {
        clock.reset();
        SecurityContextHolder.clearContext();
    }

    @Test
    void aTenantWideMonitorOfASeesOnlyAsAlerts() {
        List<OperationalAlert> seenByA = listAsMonitorOf(A);
        List<OperationalAlert> seenByB = listAsMonitorOf(B);

        assertThat(storesIn(seenByA)).containsAll(storesOfA).doesNotContainAnyElementsOf(storesOfB);
        assertThat(storesIn(seenByB)).containsAll(storesOfB).doesNotContainAnyElementsOf(storesOfA);
        assertThat(codes(seenByA)).as("B's failed export").doesNotContain("EXPORT_FAILED");
        assertThat(codes(seenByB)).contains("EXPORT_FAILED");
    }

    /** A's count (what the health check sums) does not grow when B gets more alerts. */
    @Test
    void anotherTenantsNewAlertsDoNotInflateACount() {
        clock.advance(AlertsService.CACHE_FOR.plusSeconds(1));
        List<OperationalAlert> before = tenantScoped(listAsMonitorOf(A));
        int activeBefore = alerts.activeIn(List.of(A));
        CashAlertsTestSupport.discrepancyIn(context, B, clock.instant());
        InDoubtTestSupport.overdueAttemptIn(context, B, clock.instant());
        clock.advance(AlertsService.CACHE_FOR.plusSeconds(1));

        assertThat(tenantScoped(listAsMonitorOf(A))).isEqualTo(before);
        assertThat(alerts.activeIn(List.of(A))).isEqualTo(activeBefore);
    }

    /** The alerts of the tenant-scoped sources under test (the suite's fixed test sources are left out). */
    private static List<OperationalAlert> tenantScoped(List<OperationalAlert> alerts) {
        return alerts.stream().filter(alert -> Set.of("CASH_DISCREPANCY", "PAYMENTS_IN_DOUBT").contains(alert.code()))
                .toList();
    }

    /** The AlertSource contract: every TENANT source, asked bound to A, answers nothing of B. */
    @Test
    void everyAlertSourceAnswersForTheBoundTenantOnly() {
        Map<String, AlertSource> sources = context.getBeansOfType(AlertSource.class);
        assertThat(sources).isNotEmpty();
        sources.forEach((name, source) -> {
            if (source.getClass().getEnclosingClass() == AlertsIntegrationTest.class
                    || source.scope() != AlertSource.Scope.TENANT) {
                return; // the outlet suite's fixed test sources; PLATFORM sources run with no tenant
            }
            List<OperationalAlert> answered = tenants.call(A, SystemScope.ALERTS,
                    () -> scopes.call(SystemScope.ALERTS, source::alerts));
            assertThat(storesIn(answered)).as(name).doesNotContainAnyElementsOf(storesOfB);
            assertThat(codes(answered)).as(name + ": B's failed export").doesNotContain("EXPORT_FAILED");
        });
    }

    private List<OperationalAlert> listAsMonitorOf(UUID tenant) {
        Actor monitor = Actor.account(UUID.randomUUID(), Set.of(Permission.SYSTEM_MONITOR.authority()));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(monitor, null, List.of()));
        try {
            return tenants.callIn(new TenantContext(tenant, MembershipKind.ACCOUNT, monitor.userId(), "test:monitor"),
                    () -> alerts.list().alerts());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void failAnExportIn(UUID tenant) {
        try {
            TenantTestSupport.inTenant(tenants, tenant, () -> exports.monitor(ExportMonitor.AUDIT_CSV, () -> {
                throw new IllegalStateException("B's export fails");
            }));
        } catch (IllegalStateException expected) {
            // the failure is the point: the monitor remembers it for B
        }
    }

    private static List<UUID> storesIn(List<OperationalAlert> alerts) {
        return alerts.stream().map(OperationalAlert::storeId).filter(Objects::nonNull).toList();
    }

    private static List<String> codes(List<OperationalAlert> alerts) {
        return alerts.stream().map(OperationalAlert::code).toList();
    }
}
