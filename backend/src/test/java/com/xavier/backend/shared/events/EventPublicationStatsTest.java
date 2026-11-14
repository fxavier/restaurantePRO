package com.xavier.backend.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.events.EventPublicationRecoveryTest.FlakyListener;
import com.xavier.backend.shared.events.EventPublicationRecoveryTest.ProbeHappened;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.transaction.support.TransactionTemplate;

/** D86/D88: the per-listener pending count, served by the partial index of V1006. Same context as the recovery test. */
@ApplicationModuleTest
@Import({TestcontainersConfiguration.class, EventPublicationRecoveryTest.Wiring.class,
        EventPublicationRecoveryTest.FlakyListener.class})
class EventPublicationStatsTest {

    @Autowired
    EventPublicationStats stats;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    @Test
    void pendingForCountsTheIncompletePublicationsOfTheListenersWithThePrefix() {
        long before = stats.pendingFor(FlakyListener.class.getName());

        UUID failing = UUID.randomUUID();
        FlakyListener.failures.put(failing, new AtomicInteger(Integer.MAX_VALUE));
        transaction.executeWithoutResult(tx -> events.publishEvent(new ProbeHappened(failing, UUID.randomUUID())));

        assertThat(stats.pendingFor(FlakyListener.class.getName())).isEqualTo(before + 1);
        assertThat(stats.pendingFor("com.xavier.backend.shared.events.")).isGreaterThanOrEqualTo(before + 1);
        assertThat(stats.pendingFor("com.xavier.backend.nosuchmodule.")).isZero();
        assertThat(stats.pendingFor("%")).isZero(); // a LIKE wildcard in the prefix is a literal character
    }

    @Test
    void theFilteredPendingCountKeepsTheKeyAndTheEventsUpToTheCutOffAndCountsRowsMissingTheFields() {
        String prefix = "com.xavier.backend.shared.events.FilterProbe" + UUID.randomUUID().toString().replace("-", "");
        String key = UUID.randomUUID().toString();
        Instant cutOff = Instant.parse("2026-11-07T15:00:00Z");
        pending(prefix, "{\"storeId\": \"%s\", \"confirmedAt\": \"2026-11-07T12:00:00Z\"}".formatted(key));
        pending(prefix, "{\"storeId\": \"%s\", \"at\": \"2026-11-07T14:59:59.5Z\"}".formatted(key));
        pending(prefix, "{\"storeId\": \"%s\", \"confirmedAt\": \"2026-11-07T15:00:00Z\"}".formatted(key));
        pending(prefix, "{\"storeId\": \"%s\"}".formatted(key));                            // no instant
        pending(prefix, "{\"confirmedAt\": \"2026-11-07T12:00:00Z\"}");                       // no key
        pending(prefix, "{\"storeId\": \"%s\", \"confirmedAt\": \"2026-11-07T15:00:01Z\"}".formatted(key));
        pending(prefix, "{\"storeId\": \"other\", \"confirmedAt\": \"2026-11-07T12:00:00Z\"}");
        pending("com.xavier.backend.shared.events.Elsewhere" + key,
                "{\"storeId\": \"%s\", \"confirmedAt\": \"2026-11-07T12:00:00Z\"}".formatted(key));

        try {
            long counted = stats.pendingFor(prefix, "storeId", key, List.of("confirmedAt", "at"), cutOff);

            assertThat(counted).isEqualTo(5);
            assertThat(stats.pendingFor(prefix, "storeId", key, List.of("confirmedAt"), cutOff))
                    .as("without the second field, the void-style row has no instant and counts")
                    .isEqualTo(5);
            assertThat(stats.pendingFor(prefix, "storeId", "other", List.of("confirmedAt", "at"), cutOff))
                    .as("the other key, plus the row with no key").isEqualTo(2);
        } finally {
            jdbc.sql("DELETE FROM event_publication WHERE listener_id LIKE ? OR listener_id LIKE ?")
                    .params(prefix + "%", "%Elsewhere" + key + "%").update();
        }
    }

    private void pending(String listenerId, String json) {
        jdbc.sql("""
                        INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date)
                        VALUES (?, ?, 'probe', ?, now())""")
                .params(UUID.randomUUID(), listenerId + ".on(Probe)", json).update();
    }

    @Test
    void thePendingCountIsServedByThePartialIndex() {
        // ponytail: asserts the index shape, not the plan: which index the planner picks depends on shared-DB stats
        String definition = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'event_publication_pending_ix'")
                .query(String.class).single();

        assertThat(definition).contains("(listener_id text_pattern_ops)").contains("WHERE (completion_date IS NULL)");
    }
}
