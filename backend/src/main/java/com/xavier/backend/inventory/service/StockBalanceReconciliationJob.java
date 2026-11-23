package com.xavier.backend.inventory.service;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.inventory.repository.BalanceDrift;
import com.xavier.backend.inventory.repository.BalanceProjection;
import com.xavier.backend.shared.tenancy.TenantScopes;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The nightly check of the balance projection (D113, D46): Σ movements against each balance row, in quantity and
 * value. Each mismatch is logged at ERROR with its ids, and the gauge {@code inventory_balance_drift} counts them
 * (an alert above 0; {@code POST /backoffice/inventory/balances/rebuild} repairs). The count per store is stored for
 * {@code GET /status}, which never sums the ledger itself. It also refreshes
 * {@code inventory_negative_balances{store}} (D118). An overlapping run only rewrites the same counts.
 *
 * <p>R6 (D185): the advisor runs it once per active tenant, each run in its tenant's transaction, so a run sees and
 * writes only that tenant's rows. The gauges are installation totals, so each run replaces its own tenant's share and
 * keeps the others' (the {@code store} label is a store id; there is no tenant label). Each run logs at most
 * {@code restaurantpro.jobs.batch-per-tenant} drifts (fairness, and a damaged tenant cannot flood the log); the count
 * and the returned list stay complete.
 */
@Service
public class StockBalanceReconciliationJob {

    static final String DRIFT_GAUGE = "inventory_balance_drift";
    static final String NEGATIVE_GAUGE = "inventory_negative_balances";

    private static final Logger log = LoggerFactory.getLogger(StockBalanceReconciliationJob.class);

    private final BalanceProjection projection;
    private final TenantScopes tenants;
    private final Clock clock;
    private final int logCap;
    private final Map<UUID, Integer> driftByTenant = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> negativesByTenant = new HashMap<>();
    private final MultiGauge negatives;

    StockBalanceReconciliationJob(BalanceProjection projection, TenantScopes tenants, MeterRegistry meters,
                                  Clock clock, @Value("${restaurantpro.jobs.batch-per-tenant:100}") int logCap) {
        this.projection = projection;
        this.tenants = tenants;
        this.clock = clock;
        this.logCap = logCap;
        Gauge.builder(DRIFT_GAUGE, this, StockBalanceReconciliationJob::totalDrift)
                .description("Stock balance rows that differ from the sum of their movements").register(meters);
        this.negatives = MultiGauge.builder(NEGATIVE_GAUGE)
                .description("Stock balances below zero, by store").register(meters);
    }

    /**
     * One tenant's check. Called from a thread bound to a tenant (a request, a test), it runs in that tenant only.
     * Called unbound (the scheduler), {@code StoreScopeAdvisor} runs it once per active tenant, starting at a rotating
     * tenant, and hands back only the last run's list; the scheduler ignores it. Read every tenant's outcome from the
     * gauges or from {@code balance_check}, never from this list.
     *
     * @return the drifting rows of the tenant this run checked (also logged and counted in the gauge)
     */
    @SystemScoped(SystemScope.STOCK_RECONCILE)
    @Scheduled(cron = "${restaurantpro.inventory.reconcile-cron:0 20 4 * * *}")
    @Transactional
    public synchronized List<BalanceDrift> reconcile() {
        UUID tenant = tenants.requireTenant();
        List<BalanceDrift> drifts = projection.drifts(null);
        drifts.stream().limit(logCap).forEach(d -> log.error("Stock balance drift in store {} warehouse {} stock item {}: balance {} / {}, "
                        + "movements {} / {} (quantity / value); rebuild the balances (D113)", d.storeId(),
                d.warehouseId(), d.stockItemId(), d.balanceQuantity().toPlainString(),
                d.balanceValue().toPlainString(), d.ledgerQuantity().toPlainString(),
                d.ledgerValue().toPlainString()));
        if (drifts.size() > logCap) {
            log.error("{} more stock balance drifts of tenant {} not listed (the first {} are); rebuild the balances "
                    + "(D113)", drifts.size() - logCap, tenant, logCap);
        }
        driftByTenant.put(tenant, drifts.size());
        projection.recordCheck(drifts.stream().collect(Collectors.groupingBy(BalanceDrift::storeId,
                Collectors.reducing(0, d -> 1, Integer::sum))), clock.instant());
        Map<UUID, Long> shares = new HashMap<>();
        negativesByTenant.getOrDefault(tenant, Map.of()).keySet().forEach(store -> shares.put(store, 0L));
        shares.putAll(projection.negativeBalancesPerStore());
        negativesByTenant.put(tenant, shares);
        negatives.register(negativesByTenant.values().stream().flatMap(share -> share.entrySet().stream())
                .<MultiGauge.Row<?>>map(entry -> MultiGauge.Row.of(Tags.of("store", entry.getKey().toString()),
                        entry.getValue())).toList(), true);
        return drifts;
    }

    private synchronized double totalDrift() {
        return driftByTenant.values().stream().mapToInt(Integer::intValue).sum();
    }
}
