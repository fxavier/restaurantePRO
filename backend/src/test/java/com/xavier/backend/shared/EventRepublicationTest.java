package com.xavier.backend.shared;

import com.xavier.backend.DatabaseRoles;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.BackendApplication;
import com.xavier.backend.TestcontainersConfiguration;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D19 / spec §7: an event whose listener did not complete (crash, failure) is persisted in the event
 * publication registry and is delivered again when the application restarts. The restart is a second,
 * independent application context started on the same database.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, EventRepublicationTest.ProbeListener.class})
class EventRepublicationTest {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    @Test
    void anIncompletePublicationIsDeliveredAgainAfterARestart() {
        UUID id = UUID.randomUUID();
        ProbeListener.failing.set(true);
        transaction.executeWithoutResult(tx -> events.publishEvent(new ProbeHappened(id, UUID.randomUUID())));
        awaitTrue(() -> ProbeListener.attempts.get() > 0 && incomplete(id), "first delivery attempted and failed");

        ProbeListener.failing.set(false);
        try (ConfigurableApplicationContext restarted = restart()) {
            awaitTrue(() -> ProbeListener.received.contains(id), "event delivered after restart");
            awaitTrue(() -> !incomplete(id), "publication marked completed");
        }
    }

    private ConfigurableApplicationContext restart() {
        var postgres = TestcontainersConfiguration.postgres();
        return new SpringApplicationBuilder(BackendApplication.class, ProbeListener.class)
                .properties(
                        // application.yaml reads these placeholders; builder properties have the lowest precedence
                        "RESTAURANTPRO_DB_URL=" + postgres.getJdbcUrl(),
                        // ADR 0039: the pool as the application role, Flyway as the owner (DatabaseRoles)
                        "RESTAURANTPRO_DB_USERNAME=" + DatabaseRoles.APP_ROLE,
                        "RESTAURANTPRO_DB_PASSWORD=" + DatabaseRoles.appPassword(),
                        "RESTAURANTPRO_FLYWAY_USERNAME=" + DatabaseRoles.OWNER_ROLE,
                        "RESTAURANTPRO_FLYWAY_PASSWORD=" + DatabaseRoles.ownerPassword(),
                        "RESTAURANTPRO_MANAGEMENT_PORT=0",
                        "server.port=0")
                .run();
    }

    private boolean incomplete(UUID id) {
        return jdbc.sql("""
                        SELECT count(*) FROM event_publication
                        WHERE serialized_event LIKE :id AND completion_date IS NULL
                        """)
                .param("id", "%" + id + "%").query(Long.class).single() > 0;
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    /** Carries a store, as every production event of a store listener does (D166: the listener runs in it). */
    public record ProbeHappened(UUID id, UUID storeId) {
    }

    static class ProbeListener {

        static final AtomicBoolean failing = new AtomicBoolean();
        static final AtomicInteger attempts = new AtomicInteger();
        static final Set<UUID> received = ConcurrentHashMap.newKeySet();

        @ApplicationModuleListener
        void on(ProbeHappened event) {
            attempts.incrementAndGet();
            if (failing.get()) {
                throw new IllegalStateException("listener crashed");
            }
            received.add(event.id());
        }
    }
}
