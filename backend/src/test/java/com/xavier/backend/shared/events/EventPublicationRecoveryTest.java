package com.xavier.backend.shared.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.TestcontainersConfiguration;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.config.StalenessProperties;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D88: an incomplete event publication is resubmitted by a scheduled job, without a restart, once it is older than
 * {@code resubmit-older-than}; after {@code max-attempts} attempts (Modulith counts the first delivery as attempt 1)
 * it is left FAILED for a person, and the
 * {@code event_publications} gauge counts it. The job is called directly with a clock moved past the age; its own
 * schedule waits one interval before the first run. The database is shared by the test JVM, so assertions are about
 * this test's publications only.
 */
@ApplicationModuleTest
@Import({TestcontainersConfiguration.class, EventPublicationRecoveryTest.Wiring.class,
        EventPublicationRecoveryTest.FlakyListener.class})
class EventPublicationRecoveryTest {

    static final Duration PAST_THE_AGE = Duration.ofMinutes(6);

    @Autowired
    EventPublicationRecovery recovery;

    @Autowired
    EventPublicationStats stats;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @Autowired
    MovableClock clock;

    @Autowired
    EventPublicationRegistry registry;

    @Autowired
    StalenessProperties staleness;

    @Test
    void aListenerThatFailedOnceIsCompletedByTheJobWithoutARestart() {
        UUID id = publish(1);
        awaitTrue(() -> "FAILED".equals(status(id)), "the first delivery failed");

        assertThat(attempts(id)).isOne(); // Modulith counts the first delivery as attempt 1
        recovery.resubmitDue(); // too young: left alone
        assertThat(attempts(id)).isOne();

        clock.advance(PAST_THE_AGE);
        recovery.resubmitDue();

        assertThat(completed(id)).as("publication completed by the resubmission").isTrue();
        assertThat(FlakyListener.deliveries(id)).isEqualTo(2);
        assertThat(attempts(id)).isEqualTo(2);
    }

    @Test
    void afterTenAttemptsThePublicationStaysFailedAndTheGaugeCountsIt() {
        UUID id = publish(Integer.MAX_VALUE);
        awaitTrue(() -> "FAILED".equals(status(id)), "the first delivery failed");
        stats.refresh();
        double failedBefore = failedGauge();

        for (int attempt = 2; attempt <= 10; attempt++) { // the first delivery was attempt 1
            clock.advance(PAST_THE_AGE);
            recovery.resubmitDue(); // the delivery runs on this thread: it has failed when this returns
            assertThat(attempts(id)).as("attempt %d", attempt).isEqualTo(attempt);
            assertThat(status(id)).as("attempt %d", attempt).isEqualTo("FAILED");
        }
        clock.advance(PAST_THE_AGE);
        recovery.resubmitDue();

        assertThat(attempts(id)).isEqualTo(10);
        assertThat(FlakyListener.deliveries(id)).isEqualTo(10);
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(completed(id)).isFalse();
        stats.refresh();
        assertThat(failedGauge()).isEqualTo(failedBefore + 1);
        assertThat(stats.list(EventPublicationState.FAILED, PageRequest.of(0, 200)).content())
                .anySatisfy(view -> assertThat(view.id()).isEqualTo(publicationId(id)));
        assertThat(stats.pendingFor(FlakyListener.class.getName())).isPositive();
    }

    @Test
    void aPublicationLeftResubmittedByACrashIsRecovered() {
        UUID id = publish(0);
        awaitTrue(() -> completed(id), "delivered");
        jdbc.sql("""
                        UPDATE event_publication SET completion_date = NULL, status = 'RESUBMITTED',
                               last_resubmission_date = :at
                        WHERE serialized_event LIKE :id""")
                .param("at", java.sql.Timestamp.from(clock.instant())).param("id", "%" + id + "%").update();
        FlakyListener.forget(id);

        clock.advance(Duration.ofMinutes(11)); // past the RESUBMITTED staleness (PT10M): the monitor marks it FAILED
        registry.markStalePublicationsFailed(staleness);
        recovery.resubmitDue();

        assertThat(completed(id)).as("redelivered after the staleness monitor freed it").isTrue();
        assertThat(FlakyListener.deliveries(id)).isOne();
    }

    @Test
    void theDailyPurgeDeletesOnlyCompletedPublicationsPastTheRetention() {
        UUID old = publish(0);
        UUID recent = publish(0);
        UUID oldButIncomplete = publish(Integer.MAX_VALUE);
        awaitTrue(() -> completed(old) && completed(recent) && "FAILED".equals(status(oldButIncomplete)),
                "delivered");
        // backdated rows, so no other publication in the shared database is old enough to be purged
        jdbc.sql("UPDATE event_publication SET completion_date = :at WHERE serialized_event LIKE :id")
                .param("at", java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(31))))
                .param("id", "%" + old + "%").update();
        jdbc.sql("UPDATE event_publication SET publication_date = :at WHERE serialized_event LIKE :id")
                .param("at", java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(31))))
                .param("id", "%" + oldButIncomplete + "%").update();

        recovery.purgeCompleted();

        assertThat(rows(old)).isZero();
        assertThat(rows(recent)).isOne();
        assertThat(rows(oldButIncomplete)).isOne();
    }

    private long rows(UUID id) {
        return jdbc.sql("SELECT count(*) FROM event_publication WHERE serialized_event LIKE :id")
                .param("id", "%" + id + "%").query(Long.class).single();
    }

    private UUID publish(int failures) {
        UUID id = UUID.randomUUID();
        FlakyListener.failures.put(id, new AtomicInteger(failures));
        transaction.executeWithoutResult(tx -> events.publishEvent(new ProbeHappened(id, UUID.randomUUID())));
        return id;
    }

    private double failedGauge() {
        Gauge gauge = meters.find("event_publications").tags("state", "failed", "module", "shared").gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private UUID publicationId(UUID id) {
        return jdbc.sql("SELECT id FROM event_publication WHERE serialized_event LIKE :id")
                .param("id", "%" + id + "%").query(UUID.class).single();
    }

    private String status(UUID id) {
        return jdbc.sql("SELECT status FROM event_publication WHERE serialized_event LIKE :id")
                .param("id", "%" + id + "%").query(String.class).single();
    }

    private int attempts(UUID id) {
        return jdbc.sql("SELECT completion_attempts FROM event_publication WHERE serialized_event LIKE :id")
                .param("id", "%" + id + "%").query(Integer.class).single();
    }

    private boolean completed(UUID id) {
        return jdbc.sql("SELECT completion_date IS NOT NULL FROM event_publication WHERE serialized_event LIKE :id")
                .param("id", "%" + id + "%").query(Boolean.class).single();
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** Carries a store, as every production event of a store listener does (D166: the listener runs in it). */
    public record ProbeHappened(UUID id, UUID storeId) {
    }

    /** Fails its first {@code failures} deliveries of an event, then succeeds. */
    static class FlakyListener {

        static final Map<UUID, AtomicInteger> failures = new ConcurrentHashMap<>();
        static final Map<UUID, AtomicInteger> deliveries = new ConcurrentHashMap<>();

        static int deliveries(UUID id) {
            return deliveries.getOrDefault(id, new AtomicInteger()).get();
        }

        static void forget(UUID id) {
            deliveries.remove(id);
        }

        @ApplicationModuleListener
        void on(ProbeHappened event) {
            deliveries.computeIfAbsent(event.id(), key -> new AtomicInteger()).incrementAndGet();
            if (failures.get(event.id()).getAndDecrement() > 0) {
                throw new IllegalStateException("listener failed for " + event.id());
            }
        }
    }

    /** A UTC clock in whole microseconds that only moves forward, shared by the job and the publication registry. */
    static final class MovableClock extends Clock {

        private volatile Instant now = Instant.now(Clock.systemUTC()).truncatedTo(ChronoUnit.MICROS);

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("UTC only");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Wiring {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }

        /**
         * The module listeners' executor, synchronous in this context only: a delivery (first or resubmitted) runs on
         * the calling thread and has finished, completed or FAILED, when {@code publish} or {@code resubmitDue}
         * returns. The test then asserts at once instead of polling wall-clock time (S19 flake: the 10-attempt test
         * waited up to 20 s per attempt for async deliveries and timed out once under load).
         */
        @Bean(name = "taskExecutor")
        org.springframework.core.task.SyncTaskExecutor taskExecutor() {
            return new org.springframework.core.task.SyncTaskExecutor();
        }
    }
}
