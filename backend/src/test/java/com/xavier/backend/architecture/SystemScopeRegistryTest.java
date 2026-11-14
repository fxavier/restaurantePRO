package com.xavier.backend.architecture;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * "Audited, concretely" (D166): the set of actor-less entry points and the system scope each runs in is checked in
 * here, so a new {@code @SystemScoped} method, or a scope of reach ALL, is a visible diff in review.
 */
public class SystemScopeRegistryTest {

    private static final String ROOT = "com.xavier.backend.";

    /** {@code <module>.<package>.<Class>.<method>} → its scope. */
    public static final Map<String, SystemScope> EXPECTED = new TreeMap<>(Map.ofEntries(
            entry("organization.service.CompanyBootstrapRunner.run", SystemScope.BOOTSTRAP),
            entry("identity.service.AdminBootstrapService.on", SystemScope.BOOTSTRAP),
            entry("production.service.PrintWorker.poll", SystemScope.PRINT_WORKER),
            entry("settlement.service.AttemptSweeper.sweep", SystemScope.PAYMENT_RECOVERY),
            entry("settlement.service.InDoubtResolver.poll", SystemScope.PAYMENT_RECOVERY),
            entry("settlement.service.InDoubtGauge.refresh", SystemScope.PAYMENT_RECOVERY),
            entry("settlement.service.CreditChargeSweeper.sweep", SystemScope.PAYMENT_RECOVERY),
            entry("dining.service.ReservationNoShowJob.markDue", SystemScope.RESERVATIONS),
            entry("inventory.service.StockBalanceReconciliationJob.reconcile", SystemScope.STOCK_RECONCILE),
            entry("reporting.service.RebuildService.abandonInterruptedRuns", SystemScope.REPORTING_RECOVERY),
            entry("production.service.ProductionMetrics.refreshQueue", SystemScope.METRICS),
            entry("cash.service.CashDiscrepancyAlertSource.refresh", SystemScope.ALERTS),
            entry("shared.events.EventPublicationRecovery.resubmitDue", SystemScope.EVENT_MAINTENANCE),
            entry("shared.events.EventPublicationRecovery.purgeCompleted", SystemScope.EVENT_MAINTENANCE),
            entry("shared.events.EventPublicationStats.refresh", SystemScope.EVENT_MAINTENANCE),
            entry("shared.events.EventPublicationAlertSource.refreshGauge", SystemScope.EVENT_MAINTENANCE),
            entry("identity.service.LoginThrottling.purgeExpired", SystemScope.IDENTITY_MAINTENANCE),
            entry("identity.service.SupportGrantExpiry.expireDue", SystemScope.IDENTITY_MAINTENANCE),
            entry("shared.idempotency.IdempotencyRetention.purgeExpired", SystemScope.HOUSEKEEPING),
            entry("shared.notify.ChangeNotifier.heartbeat", SystemScope.HOUSEKEEPING)));

    @Test
    void theSystemScopedEntryPointsAreTheCheckedInOnes() {
        Map<String, SystemScope> actual = new TreeMap<>();
        for (JavaMethod method : ArchitectureTests.PRODUCTION.stream()
                .flatMap(type -> type.getMethods().stream()).toList()) {
            method.tryGetAnnotationOfType(SystemScoped.class).ifPresent(scoped -> actual.put(
                    method.getOwner().getName().substring(ROOT.length()) + "." + method.getName(), scoped.value()));
        }

        assertThat(actual).isEqualTo(EXPECTED);
    }
}
