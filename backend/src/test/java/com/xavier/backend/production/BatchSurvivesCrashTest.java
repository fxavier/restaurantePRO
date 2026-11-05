package com.xavier.backend.production;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.xavier.backend.BackendApplication;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersProductionApi;
import com.xavier.backend.production.ProductionFixtures.Round;
import com.xavier.backend.production.model.CodePage;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.AopTestUtils;

/**
 * D50 §7.1 / A4 (production half), A5 (restart half): the round's {@code OrderBatchConfirmed} publication commits
 * with the round. When production crashes before it finishes (here: the dispatch call fails, so its ticket and job
 * roll back), the publication stays incomplete; after a restart (a second application on the same database) it is
 * delivered again, and that yields exactly one ticket and one job, which the restarted worker prints once.
 */
@ProductionModuleTest
class BatchSurvivesCrashTest {

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    OrdersProductionApi ordersProductionApi;

    @Autowired
    JdbcClient jdbc;

    @Test
    void anIncompleteRoundIsDeliveredAfterARestartAndYieldsExactlyOneTicket() throws Exception {
        Shop shop = fixtures.shop();
        try (TcpFakePrinter printer = TcpFakePrinter.accepting()) {
            fixtures.barPrinter(shop.store(), printer.host(), printer.port());
            // the spy sits behind the transactional proxy: stub and verify it there
            OrdersProductionApi spy = AopTestUtils.getUltimateTargetObject(ordersProductionApi);
            doThrow(new IllegalStateException("crash before production finished"))
                    .when(spy).markBatchDispatched(any());
            Round round;
            try {
                round = fixtures.confirmedRound(shop, UUID.randomUUID(), UUID.randomUUID(), "1");
                verify(spy, timeout(10_000)).markBatchDispatched(round.batch());
                await(() -> incomplete(round.batch()), "publication left incomplete");
            } finally {
                reset(spy);
            }
            assertThat(fixtures.tickets(round.batch())).isEmpty();
            assertThat(fixtures.batchStatus(round.batch())).isEqualTo("CONFIRMED");

            try (ConfigurableApplicationContext restarted = restart()) {
                awaitLong(() -> "DISPATCHED".equals(fixtures.batchStatus(round.batch())), "dispatched after restart");
                awaitLong(() -> !incomplete(round.batch()), "publication completed");
                awaitLong(() -> fixtures.jobsOfSource(round.batch()).stream()
                        .anyMatch(job -> "SENT".equals(job.get("status"))), "printed by the restarted worker");
            }

            assertThat(fixtures.tickets(round.batch())).hasSize(1);
            assertThat(fixtures.jobsOfSource(round.batch())).hasSize(1);
            assertThat(printer.awaitPayloads(CodePage.CP860.charset(),
                    text -> text.contains("Pedido #" + round.orderNumber()), 1, Duration.ofSeconds(5))).hasSize(1);
        }
    }

    private boolean incomplete(UUID batch) {
        return jdbc.sql("""
                        SELECT count(*) FROM event_publication
                        WHERE serialized_event LIKE :batch AND listener_id LIKE '%ProductionListeners%'
                          AND completion_date IS NULL""")
                .param("batch", "%" + batch + "%").query(Long.class).single() > 0;
    }

    private static ConfigurableApplicationContext restart() {
        var postgres = TestcontainersConfiguration.postgres();
        return new SpringApplicationBuilder(BackendApplication.class)
                .properties(
                        // application.yaml reads these placeholders; builder properties have the lowest precedence
                        "RESTAURANTPRO_DB_URL=" + postgres.getJdbcUrl(),
                        "RESTAURANTPRO_DB_USERNAME=" + postgres.getUsername(),
                        "RESTAURANTPRO_DB_PASSWORD=" + postgres.getPassword(),
                        "RESTAURANTPRO_MANAGEMENT_PORT=0",
                        "server.port=0")
                .run();
    }

    private static void awaitLong(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            Thread.sleep(100);
        }
    }
}
