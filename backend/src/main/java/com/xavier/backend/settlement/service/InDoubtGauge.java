package com.xavier.backend.settlement.service;

import com.xavier.backend.settlement.repository.InDoubtAttempt;
import com.xavier.backend.settlement.repository.PaymentAttemptRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    private static final Logger log = LoggerFactory.getLogger(InDoubtGauge.class);

    private final PaymentAttemptRepository attempts;
    private final Clock clock;
    private final MultiGauge inDoubt;
    private final MultiGauge overdue;
    private final Set<UUID> stores = new HashSet<>();
    private final Set<UUID> warned = new HashSet<>();

    InDoubtGauge(MeterRegistry meters, PaymentAttemptRepository attempts, Clock clock) {
        this.attempts = attempts;
        this.clock = clock;
        this.inDoubt = MultiGauge.builder("payment_attempts_in_doubt")
                .description("Payment attempts whose outcome is unknown and must be resolved").register(meters);
        this.overdue = MultiGauge.builder("payment_attempts_in_doubt_overdue")
                .description("Payment attempts in doubt for more than " + ALERT_AFTER).register(meters);
    }

    @Scheduled(fixedDelayString = "${restaurantpro.settlement.in-doubt-poll-interval:PT30S}")
    public synchronized void refresh() {
        Instant overdueBefore = clock.instant().minus(ALERT_AFTER);
        List<InDoubtAttempt> unknown = attempts.findInDoubt();
        List<InDoubtAttempt> late = unknown.stream().filter(a -> a.createdAt().isBefore(overdueBefore)).toList();
        Map<UUID, Long> perStore = perStore(unknown);
        stores.addAll(perStore.keySet());
        inDoubt.register(rows(perStore), true);
        overdue.register(rows(perStore(late)), true);
        warned.retainAll(late.stream().map(InDoubtAttempt::id).collect(Collectors.toSet()));
        late.stream().filter(a -> warned.add(a.id())).forEach(a -> log.warn(
                "Payment attempt {} of store {} has been in doubt since {} (over {}): query the provider or resolve "
                        + "it (D81)", a.id(), a.storeId(), a.createdAt(), ALERT_AFTER));
    }

    private static Map<UUID, Long> perStore(List<InDoubtAttempt> attempts) {
        return attempts.stream().collect(Collectors.groupingBy(InDoubtAttempt::storeId, Collectors.counting()));
    }

    private List<MultiGauge.Row<?>> rows(Map<UUID, Long> perStore) {
        return stores.stream().<MultiGauge.Row<?>>map(store -> MultiGauge.Row.of(Tags.of("store", store.toString()),
                perStore.getOrDefault(store, 0L))).toList();
    }
}
