package com.xavier.backend.settlement.service;

import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.settlement.repository.InDoubtAttempt;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import com.xavier.backend.shared.tenancy.TenantScopes;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * D81 (4), §12: {@code payment_attempts_in_doubt{store}}, the UNKNOWN attempts waiting for a resolve (PRD S16, §7.4),
 * and the alert metric {@code payment_attempts_in_doubt_overdue{store}}, those in doubt for more than
 * {@link #ALERT_AFTER}; each overdue attempt is logged at WARN once. An attempt's age is counted from its creation, as
 * the acceptance window is. Refreshed with the in-doubt poller's interval; a store once seen keeps reporting, at 0
 * when nothing is in doubt.
 */
@Component
class InDoubtGauge {

    static final Duration ALERT_AFTER = Duration.ofMinutes(30);
    /** A tenant not run for this long (suspended, closed) is no longer reported: its state is dropped. */
    static final Duration STALE_AFTER = Duration.ofMinutes(5);

    private static final Logger log = LoggerFactory.getLogger(InDoubtGauge.class);

    private final PaymentAttemptRepository attempts;
    private final Clock clock;
    private final MultiGauge inDoubt;
    private final MultiGauge overdue;
    private final TenantScopes tenants;
    private final Map<UUID, TenantState> perTenant = new ConcurrentHashMap<>();

    /** A store's attempts that were overdue at the last refresh, and when the oldest of them was created. */
    record OverdueInStore(UUID storeId, long attempts, Instant oldestCreatedAt) {
    }

    /** What the last run of one tenant saw; {@link #refresh} (synchronized) writes it, the alert reads {@code lastOverdue}. */
    private static final class TenantState {
        final Set<UUID> stores = new HashSet<>();
        final Set<UUID> warned = new HashSet<>();
        Map<UUID, Long> inDoubt = Map.of();
        Map<UUID, Long> overdue = Map.of();
        volatile List<OverdueInStore> lastOverdue = List.of();
        Instant ranAt = Instant.MIN;
    }

    InDoubtGauge(MeterRegistry meters, PaymentAttemptRepository attempts, Clock clock, TenantScopes tenants) {
        this.attempts = attempts;
        this.clock = clock;
        this.tenants = tenants;
        this.inDoubt = MultiGauge.builder("payment_attempts_in_doubt")
                .description("Payment attempts whose outcome is unknown and must be resolved").register(meters);
        this.overdue = MultiGauge.builder("payment_attempts_in_doubt_overdue")
                .description("Payment attempts in doubt for more than " + ALERT_AFTER).register(meters);
    }

    @SystemScoped(SystemScope.PAYMENT_RECOVERY)
    @Scheduled(fixedDelayString = "${restaurantpro.settlement.in-doubt-poll-interval:PT30S}")
    public synchronized void refresh() {
        TenantState state = perTenant.computeIfAbsent(tenants.requireTenant(), tenant -> new TenantState());
        Instant now = clock.instant();
        state.ranAt = now;
        perTenant.values().removeIf(other -> other.ranAt.isBefore(now.minus(STALE_AFTER)));
        Instant overdueBefore = now.minus(ALERT_AFTER);
        List<InDoubtAttempt> unknown = attempts.findInDoubt();
        List<InDoubtAttempt> late = unknown.stream().filter(a -> a.createdAt().isBefore(overdueBefore)).toList();
        state.inDoubt = perStore(unknown);
        state.overdue = perStore(late);
        state.stores.addAll(state.inDoubt.keySet());
        state.lastOverdue = late.stream().collect(Collectors.groupingBy(InDoubtAttempt::storeId)).entrySet().stream()
                .map(store -> new OverdueInStore(store.getKey(), store.getValue().size(), store.getValue().stream()
                        .map(InDoubtAttempt::createdAt).min(Comparator.naturalOrder()).orElseThrow()))
                .toList();
        inDoubt.register(rows(tenant -> tenant.inDoubt), true);
        overdue.register(rows(tenant -> tenant.overdue), true);
        state.warned.retainAll(late.stream().map(InDoubtAttempt::id).collect(Collectors.toSet()));
        late.stream().filter(a -> state.warned.add(a.id())).forEach(a -> log.warn(
                "Payment attempt {} of store {} has been in doubt since {} (over {}): query the provider or resolve "
                        + "it (D81)", a.id(), a.storeId(), a.createdAt(), ALERT_AFTER));
    }

    /** Every tenant's overdue attempts per store as of its last {@link #refresh}, without a query. */
    List<OverdueInStore> overdueAtLastRefresh() {
        return perTenant.values().stream().flatMap(state -> state.lastOverdue.stream()).toList();
    }

    private static Map<UUID, Long> perStore(List<InDoubtAttempt> attempts) {
        return attempts.stream().collect(Collectors.groupingBy(InDoubtAttempt::storeId, Collectors.counting()));
    }

    /** One row per store of every tenant seen so far, at the count {@code counts} gives its tenant's last run. */
    private List<MultiGauge.Row<?>> rows(java.util.function.Function<TenantState, Map<UUID, Long>> counts) {
        Map<UUID, Long> rows = new HashMap<>();
        perTenant.values().forEach(state -> state.stores.forEach(store -> rows.put(store,
                counts.apply(state).getOrDefault(store, 0L))));
        return rows.entrySet().stream().<MultiGauge.Row<?>>map(row -> MultiGauge.Row.of(
                Tags.of("store", row.getKey().toString()), row.getValue())).toList();
    }
}
