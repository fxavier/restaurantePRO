package com.xavier.backend.inventory.service;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.inventory.repository.BalanceDrift;
import com.xavier.backend.inventory.repository.BalanceProjection;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The nightly check of the balance projection (D113, D46): Σ movements against each balance row, in quantity and
 * value. Each mismatch is logged at ERROR with its ids, and the gauge {@code inventory_balance_drift} counts them
 * (an alert above 0; {@code POST /backoffice/inventory/balances/rebuild} repairs). The count per store is stored for
 * {@code GET /status}, which never sums the ledger itself. It also refreshes
 * {@code inventory_negative_balances{store}} (D118). An overlapping run only rewrites the same counts.
 */
@Service
public class StockBalanceReconciliationJob {

    static final String DRIFT_GAUGE = "inventory_balance_drift";
    static final String NEGATIVE_GAUGE = "inventory_negative_balances";

    private static final Logger log = LoggerFactory.getLogger(StockBalanceReconciliationJob.class);

    private final BalanceProjection projection;
    private final Clock clock;
    private final AtomicLong drift = new AtomicLong();
    private final MultiGauge negatives;
    private final Set<UUID> storesSeen = new HashSet<>();

    StockBalanceReconciliationJob(BalanceProjection projection, MeterRegistry meters, Clock clock) {
        this.projection = projection;
        this.clock = clock;
        Gauge.builder(DRIFT_GAUGE, drift, AtomicLong::get)
                .description("Stock balance rows that differ from the sum of their movements").register(meters);
        this.negatives = MultiGauge.builder(NEGATIVE_GAUGE)
                .description("Stock balances below zero, by store").register(meters);
    }

    /** @return the drifting rows found (also logged and counted in the gauge) */
    @SystemScoped(SystemScope.STOCK_RECONCILE)
    @Scheduled(cron = "${restaurantpro.inventory.reconcile-cron:0 20 4 * * *}")
    @Transactional
    public synchronized List<BalanceDrift> reconcile() {
        List<BalanceDrift> drifts = projection.drifts(null);
        drifts.forEach(d -> log.error("Stock balance drift in store {} warehouse {} stock item {}: balance {} / {}, "
                        + "movements {} / {} (quantity / value); rebuild the balances (D113)", d.storeId(),
                d.warehouseId(), d.stockItemId(), d.balanceQuantity().toPlainString(),
                d.balanceValue().toPlainString(), d.ledgerQuantity().toPlainString(),
                d.ledgerValue().toPlainString()));
        drift.set(drifts.size());
        projection.recordCheck(drifts.stream().collect(Collectors.groupingBy(BalanceDrift::storeId,
                Collectors.reducing(0, d -> 1, Integer::sum))), clock.instant());
        Map<UUID, Long> perStore = projection.negativeBalancesPerStore();
        storesSeen.addAll(perStore.keySet());
        negatives.register(storesSeen.stream().<MultiGauge.Row<?>>map(store -> MultiGauge.Row.of(
                Tags.of("store", store.toString()), perStore.getOrDefault(store, 0L))).toList(), true);
        return drifts;
    }
}
