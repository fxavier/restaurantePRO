package com.xavier.backend.shared.events;

import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.AlertSource;
import com.xavier.backend.shared.ops.OperationalAlert;
import com.xavier.backend.shared.web.PageResponse;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The event alerts of D150, installation-wide: {@code EVENTS_FAILED} (CRITICAL) while a publication is {@code failed}
 * (the recovery job gave up on it, D88), and {@code EVENTS_BACKLOG} (WARNING) while the oldest incomplete one is older
 * than {@link #BACKLOG_AFTER}. Gauge {@code event_publications_oldest_incomplete_seconds} (0 when none), refreshed with
 * the recovery job's interval and on every {@link #alerts} call.
 */
@Component
class EventPublicationAlertSource implements AlertSource {

    static final Duration BACKLOG_AFTER = Duration.ofMinutes(30);

    private final EventPublicationStats stats;
    private final Clock clock;
    private final AtomicReference<Optional<Instant>> oldestIncomplete = new AtomicReference<>(Optional.empty());

    EventPublicationAlertSource(EventPublicationStats stats, MeterRegistry meters, Clock clock) {
        this.stats = stats;
        this.clock = clock;
        Gauge.builder("event_publications_oldest_incomplete_seconds", this, EventPublicationAlertSource::oldestAge)
                .description("Age of the oldest event publication still waiting for its listener").register(meters);
    }

    @Override
    public List<OperationalAlert> alerts() {
        Optional<Instant> oldest = refresh();
        List<OperationalAlert> alerts = new ArrayList<>();
        PageResponse<EventPublicationView> failed = stats.list(EventPublicationState.FAILED, PageRequest.of(0, 1));
        if (failed.totalElements() > 0) {
            alerts.add(new OperationalAlert("EVENTS_FAILED", AlertSeverity.CRITICAL, null, failed.totalElements(),
                    failed.content().isEmpty() ? clock.instant() : failed.content().getFirst().publicationDate(),
                    failed.totalElements() + " event publication(s) failed for good: resubmit them in System."));
        }
        oldest.filter(instant -> instant.isBefore(clock.instant().minus(BACKLOG_AFTER)))
                .ifPresent(instant -> alerts.add(new OperationalAlert("EVENTS_BACKLOG", AlertSeverity.WARNING, null,
                        1, instant, "An event publication has waited more than 30 min for its listener.")));
        return alerts;
    }

    /** Reads the oldest incomplete publication again for the gauge. */
    @Scheduled(fixedDelayString = "${restaurantpro.events.resubmit-interval:PT1M}")
    void refreshGauge() {
        refresh();
    }

    private Optional<Instant> refresh() {
        Optional<Instant> oldest = stats.oldestIncomplete();
        oldestIncomplete.set(oldest);
        return oldest;
    }

    private double oldestAge() {
        return oldestIncomplete.get()
                .map(instant -> Duration.between(instant, clock.instant()).toMillis() / 1000.0)
                .orElse(0.0);
    }
}
