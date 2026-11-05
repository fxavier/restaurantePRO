package com.xavier.backend.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.events.EventPublicationRecoveryTest.FlakyListener;
import com.xavier.backend.shared.events.EventPublicationRecoveryTest.ProbeHappened;
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
        transaction.executeWithoutResult(tx -> events.publishEvent(new ProbeHappened(failing)));

        assertThat(stats.pendingFor(FlakyListener.class.getName())).isEqualTo(before + 1);
        assertThat(stats.pendingFor("com.xavier.backend.shared.events.")).isGreaterThanOrEqualTo(before + 1);
        assertThat(stats.pendingFor("com.xavier.backend.nosuchmodule.")).isZero();
        assertThat(stats.pendingFor("%")).isZero(); // a LIKE wildcard in the prefix is a literal character
    }

    @Test
    void thePendingCountIsServedByThePartialIndex() {
        List<String> plan = transaction.execute(tx -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return jdbc.sql("""
                            EXPLAIN SELECT count(*) FROM event_publication
                            WHERE completion_date IS NULL AND listener_id LIKE 'com.xavier.backend.reporting.%'""")
                    .query(String.class).list();
        });

        assertThat(String.join("\n", plan)).contains("event_publication_pending_ix");
    }
}
