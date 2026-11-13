package com.xavier.backend.cash.service;

import com.xavier.backend.cash.model.CashClosingStatus;
import com.xavier.backend.cash.repository.CashClosingRepository;
import com.xavier.backend.cash.repository.StoreCashDiscrepancy;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
 * minute into the gauge {@code cash_closings_with_difference{store}}; a store once seen keeps reporting, at 0 when
 * clear. {@link #alerts} reads the last refresh.
 */
@Component
class CashDiscrepancyAlertSource implements AlertSource {

    static final Duration WINDOW = Duration.ofHours(24);
    private static final Set<CashClosingStatus> COUNTED = Set.of(CashClosingStatus.SUBMITTED,
            CashClosingStatus.APPROVED);

    private final CashClosingRepository closings;
    private final Clock clock;
    private final BigDecimal threshold;
    private final MultiGauge gauge;
    private final Set<UUID> stores = ConcurrentHashMap.newKeySet();
    private volatile List<StoreCashDiscrepancy> last = List.of();

    CashDiscrepancyAlertSource(CashClosingRepository closings, MeterRegistry meters, Clock clock,
                               @Value("${restaurantpro.alerts.cash-difference-threshold:0}") BigDecimal threshold) {
        this.closings = closings;
        this.clock = clock;
        this.threshold = threshold;
        this.gauge = MultiGauge.builder("cash_closings_with_difference")
                .description("Cash closings submitted or approved in the last 24 h with a difference over the "
                        + "threshold").register(meters);
    }

    @Scheduled(fixedDelayString = "PT1M")
    void refresh() {
        List<StoreCashDiscrepancy> current = closings.discrepanciesSince(COUNTED, clock.instant().minus(WINDOW),
                threshold);
        Map<UUID, Long> perStore = current.stream()
                .collect(Collectors.toMap(StoreCashDiscrepancy::storeId, StoreCashDiscrepancy::closings));
        stores.addAll(perStore.keySet());
        gauge.register(stores.stream().<MultiGauge.Row<?>>map(store -> MultiGauge.Row.of(
                Tags.of("store", store.toString()), perStore.getOrDefault(store, 0L))).toList(), true);
        last = current;
    }

    @Override
    public List<OperationalAlert> alerts() {
        return last.stream()
                .map(store -> new OperationalAlert("CASH_DISCREPANCY", AlertSeverity.WARNING, store.storeId(),
                        store.closings(), store.firstSubmittedAt(), store.closings()
                        + " cash closing(s) of the last 24 h with a difference over the threshold."))
                .toList();
    }
}
