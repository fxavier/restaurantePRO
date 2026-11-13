package com.xavier.backend.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.OperationalAlert;
import com.xavier.backend.shared.web.PageResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D150 EVENTS_FAILED (a publication the recovery job gave up on: CRITICAL) and EVENTS_BACKLOG (the oldest incomplete
 * publication older than 30 min: WARNING), with the gauge {@code event_publications_oldest_incomplete_seconds}. The
 * query runs on the shared database (rows written here, deleted after); the thresholds on a stubbed read side. Same
 * context as the recovery test.
 */
@ApplicationModuleTest
@Import({TestcontainersConfiguration.class, EventPublicationRecoveryTest.Wiring.class,
        EventPublicationRecoveryTest.FlakyListener.class})
class EventPublicationAlertSourceTest {

    static final String LISTENER = "com.xavier.backend.shared.events.EventPublicationAlertSourceTest$Gone.on(x)";
    static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    @Autowired
    EventPublicationStats stats;

    @Autowired
    EventPublicationAlertSource source;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transaction;

    /**
     * The rows are written and read in one transaction that is rolled back: other cached contexts' recovery jobs share
     * this database and would otherwise see (and resubmit) these very old incomplete publications.
     */
    @Test
    void theOldestIncompletePublicationIgnoresCompletedAndExhaustedOnes() {
        Instant completed = Instant.parse("1998-01-01T00:00:00Z");
        Instant exhausted = Instant.parse("1999-01-01T00:00:00Z");
        Instant incomplete = Instant.parse("2000-01-01T00:00:00Z");
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            publication(completed, 1, true);
            publication(exhausted, 10, false);
            publication(incomplete, 1, false);

            assertThat(stats.oldestIncomplete()).contains(incomplete);
            List<OperationalAlert> alerts = source.alerts();
            assertThat(alerts)
                    .extracting(OperationalAlert::code, OperationalAlert::severity, OperationalAlert::storeId)
                    .contains(tuple("EVENTS_FAILED", AlertSeverity.CRITICAL, null),
                            tuple("EVENTS_BACKLOG", AlertSeverity.WARNING, null));
            assertThat(alert(alerts, "EVENTS_BACKLOG").since()).isEqualTo(incomplete);
            assertThat(alert(alerts, "EVENTS_FAILED").since()).isEqualTo(exhausted);
        });
    }

    @Test
    void anExhaustedPublicationIsCriticalWithTheFailedCount() {
        EventPublicationStats stubbed = stubbed(Optional.empty(), 3, NOW.minusSeconds(60));

        assertThat(sourceOn(stubbed).alerts())
                .extracting(OperationalAlert::code, OperationalAlert::severity, OperationalAlert::count,
                        OperationalAlert::since)
                .containsExactly(tuple("EVENTS_FAILED", AlertSeverity.CRITICAL, 3L, NOW.minusSeconds(60)));
    }

    @Test
    void anIncompletePublicationIsABacklogOnlyWhenOlderThan30Minutes() {
        Instant thirtyMinutesAgo = NOW.minus(Duration.ofMinutes(30));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        EventPublicationAlertSource atTheLimit = new EventPublicationAlertSource(
                stubbed(Optional.of(thirtyMinutesAgo), 0, null), meters, fixed());
        assertThat(atTheLimit.alerts()).isEmpty();
        assertThat(meters.get("event_publications_oldest_incomplete_seconds").gauge().value()).isEqualTo(1800);

        Instant older = thirtyMinutesAgo.minusSeconds(1);
        assertThat(sourceOn(stubbed(Optional.of(older), 0, null)).alerts())
                .extracting(OperationalAlert::code, OperationalAlert::severity, OperationalAlert::since)
                .containsExactly(tuple("EVENTS_BACKLOG", AlertSeverity.WARNING, older));
    }

    @Test
    void nothingIncompleteIsNoAlertAndAZeroGauge() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EventPublicationAlertSource quiet = new EventPublicationAlertSource(stubbed(Optional.empty(), 0, null),
                meters, fixed());

        assertThat(quiet.alerts()).isEmpty();
        assertThat(meters.get("event_publications_oldest_incomplete_seconds").gauge().value()).isZero();
    }

    private static EventPublicationStats stubbed(Optional<Instant> oldestIncomplete, long failed,
                                                 Instant oldestFailed) {
        EventPublicationStats stats = mock(EventPublicationStats.class);
        when(stats.oldestIncomplete()).thenReturn(oldestIncomplete);
        List<EventPublicationView> page = oldestFailed == null ? List.of()
                : List.of(new EventPublicationView(UUID.randomUUID(), LISTENER, "x", oldestFailed, null, 10,
                "FAILED", EventPublicationState.FAILED));
        when(stats.list(eq(EventPublicationState.FAILED), any(Pageable.class)))
                .thenReturn(new PageResponse<>(page, 0, 1, failed));
        return stats;
    }

    private static EventPublicationAlertSource sourceOn(EventPublicationStats stats) {
        return new EventPublicationAlertSource(stats, new SimpleMeterRegistry(), fixed());
    }

    private static Clock fixed() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static OperationalAlert alert(List<OperationalAlert> alerts, String code) {
        return alerts.stream().filter(alert -> alert.code().equals(code)).findFirst().orElseThrow();
    }

    private UUID publication(Instant at, int attempts, boolean completed) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date,
                                                       completion_date, status, completion_attempts)
                        VALUES (:id, :listener, 'x', '{}', :at, :completedAt, :status, :attempts)""")
                .param("id", id).param("listener", LISTENER).param("at", Timestamp.from(at))
                .param("completedAt", completed ? Timestamp.from(at) : null)
                .param("status", completed ? "COMPLETED" : "FAILED").param("attempts", attempts).update();
        return id;
    }
}
