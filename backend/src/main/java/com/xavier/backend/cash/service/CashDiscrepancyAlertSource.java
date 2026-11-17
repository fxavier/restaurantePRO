package com.xavier.backend.cash.service;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.cash.model.CashClosingStatus;
import com.xavier.backend.cash.repository.CashClosingRepository;
import com.xavier.backend.cash.repository.StoreCashDiscrepancy;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import com.xavier.backend.shared.tenancy.TenantScopes;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * D150 CASH_DISCREPANCY (WARNING, per store): closings submitted or approved in the last 24 h whose Σ |difference|
 * exceeds {@code restaurantpro.alerts.cash-difference-threshold} (default 0, so any difference). Refreshed every
 * {@code restaurantpro.alerts.cash-discrepancy-interval} (default a minute) into the gauge
 * {@code cash_closings_with_difference{store}}; a store once seen keeps reporting, at 0 when clear. The scheduler runs
 * it once per tenant on this one bean (D185), so each tenant's last result is kept apart and a tenant's run replaces
 * only its own: the gauge (no tenant label) covers every tenant, {@link #alerts} only the bound one.
 */
@Component
class CashDiscrepancyAlertSource implements AlertSource {

    static final Duration WINDOW = Duration.ofHours(24);
    /** A tenant not run for this many refresh intervals (suspended, closed) is no longer reported: state dropped. */
    private static final int STALE_INTERVALS = 3;
    private static final Set<CashClosingStatus> COUNTED = Set.of(CashClosingStatus.SUBMITTED,
            CashClosingStatus.APPROVED);

    private final CashClosingRepository closings;
    private final Clock clock;
    private final BigDecimal threshold;
    private final MultiGauge gauge;
    private final TenantScopes tenants;
    private final Duration staleAfter;
    private final Map<UUID, Set<UUID>> storesOf = new ConcurrentHashMap<>();
    private final Map<UUID, List<StoreCashDiscrepancy>> lastOf = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> ranAt = new ConcurrentHashMap<>();

    CashDiscrepancyAlertSource(CashClosingRepository closings, MeterRegistry meters, Clock clock,
                               @Value("${restaurantpro.alerts.cash-difference-threshold:0}") BigDecimal threshold,
                               TenantScopes tenants,
                               @Value("${restaurantpro.alerts.cash-discrepancy-interval:PT1M}")
                               Duration refreshInterval) {
        this.closings = closings;
        this.clock = clock;
        this.tenants = tenants;
        this.threshold = threshold;
        this.staleAfter = refreshInterval.multipliedBy(STALE_INTERVALS);
        this.gauge = MultiGauge.builder("cash_closings_with_difference")
                .description("Cash closings submitted or approved in the last 24 h with a difference over the "
                        + "threshold").register(meters);
    }

    @SystemScoped(SystemScope.ALERTS)
    @Scheduled(fixedDelayString = "${restaurantpro.alerts.cash-discrepancy-interval:PT1M}")
    void refresh() {
        UUID tenant = tenants.requireTenant();
        Instant now = clock.instant();
        ranAt.put(tenant, now);
        ranAt.entrySet().removeIf(run -> {
            boolean stale = run.getValue().isBefore(now.minus(staleAfter));
            if (stale) {
                storesOf.remove(run.getKey());
                lastOf.remove(run.getKey());
            }
            return stale;
        });
        List<StoreCashDiscrepancy> current = closings.discrepanciesSince(COUNTED, now.minus(WINDOW), threshold);
        Map<UUID, Long> perStore = current.stream()
                .collect(Collectors.toMap(StoreCashDiscrepancy::storeId, StoreCashDiscrepancy::closings));
        storesOf.computeIfAbsent(tenant, key -> ConcurrentHashMap.newKeySet()).addAll(perStore.keySet());
        lastOf.put(tenant, current);
        Map<UUID, Long> counts = new HashMap<>();
        lastOf.values().forEach(found -> found.forEach(store -> counts.put(store.storeId(), store.closings())));
        gauge.register(storesOf.values().stream().flatMap(Set::stream).distinct()
                .<MultiGauge.Row<?>>map(store -> MultiGauge.Row.of(Tags.of("store", store.toString()),
                        counts.getOrDefault(store, 0L))).toList(), true);
    }

    /** The bound tenant's discrepancies as of its last refresh (security rule 7: never another tenant's). */
    @Override
    public List<OperationalAlert> alerts() {
        return lastOf.getOrDefault(tenants.requireTenant(), List.of()).stream()
                .map(store -> new OperationalAlert("CASH_DISCREPANCY", AlertSeverity.WARNING, store.storeId(),
                        store.closings(), store.firstSubmittedAt(), store.closings()
                        + " cash closing(s) of the last 24 h with a difference over the threshold."))
                .toList();
    }
}
