package com.xavier.backend.orders.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.delivery.DeliveryFixtures.DeliveryShop;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D133 "delivered and cancelled at once": two transitions of one delivery from two terminals. Deterministic
 * interleaving: a third transaction holds the delivery row, both requests read the same version and queue on their
 * UPDATE (seen in {@code pg_stat_activity}), then the holder lets go. The delivery's {@code @Version} lets exactly one
 * win; the other is a 409 and leaves nothing behind (no event, no order change).
 */
@OrdersModuleTest
class DeliveryTransitionRaceTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    DeliveryFixtures deliveries;
    DeliveryShop d;

    @BeforeEach
    void aDeliveryShop() throws Exception {
        deliveries = new DeliveryFixtures(mvc, jdbc, fixtures, customers);
        d = deliveries.shop();
    }

    @Test
    void deliveredAndReturnedAtOnceExactlyOneWins() throws Exception {
        UUID order = deliveries.confirmedDelivery(d, employee, terminal);
        deliveries.dispatch(d, order, 0, d.courier()).andExpect(status().isOk());

        List<Integer> statuses = whileHeld(order,
                () -> deliveries.delivered(d, order, 1).andReturn().getResponse().getStatus(),
                () -> deliveries.returned(d, order, 1).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        String winner = statuses.get(0) == 200 ? "DELIVERED" : "RETURNED";
        assertThat(deliveries.deliveryStatus(order)).isEqualTo(winner);
        assertThat(deliveries.history(order)).containsExactly("->PENDING", "PENDING>OUT_FOR_DELIVERY",
                "OUT_FOR_DELIVERY>" + winner);
    }

    @Test
    void aCancelAndARedispatchAtOnceExactlyOneWinsAndTheOrderAgreesWithTheDelivery() throws Exception {
        UUID order = deliveries.confirmedDelivery(d, employee, terminal);
        deliveries.dispatch(d, order, 0, d.courier()).andExpect(status().isOk());
        deliveries.returned(d, order, 1).andExpect(status().isOk());
        deliveries.voidAll(order);
        long orderVersion = deliveries.orderVersion(order);

        List<Integer> statuses = whileHeld(order,
                () -> deliveries.cancel(d, order, orderVersion).andReturn().getResponse().getStatus(),
                () -> deliveries.dispatch(d, order, 2, d.courier()).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        if (statuses.get(0) == 200) {
            assertThat(deliveries.lifecycle(order)).isEqualTo("CANCELLED");
            assertThat(deliveries.deliveryStatus(order)).isEqualTo("CANCELLED");
        } else {
            assertThat(deliveries.lifecycle(order)).isEqualTo("OPEN");
            assertThat(deliveries.orderVersion(order)).isEqualTo(orderVersion);
            assertThat(deliveries.deliveryStatus(order)).isEqualTo("OUT_FOR_DELIVERY");
        }
        assertThat(deliveries.history(order)).hasSize(4);
    }

    /** Runs both requests while a third transaction holds the delivery row; both must queue on it. */
    List<Integer> whileHeld(UUID order, Callable<Integer> first, Callable<Integer> second) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
                    status -> {
                        jdbc.sql("SELECT order_id FROM orders.delivery WHERE order_id = ? FOR UPDATE").param(order)
                                .query(UUID.class).single();
                        held.countDown();
                        await(release);
                    }));
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Integer> a = pool.submit(first);
            Future<Integer> b = pool.submit(second);
            waitUntil(() -> a.isDone() || b.isDone() || waitingOnTheDelivery() == 2);
            assertThat(a.isDone() || b.isDone()).as("both must queue on the delivery row").isFalse();
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    long waitingOnTheDelivery() {
        return jdbc.sql("""
                SELECT count(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND query ILIKE 'update orders.delivery set%'""")
                .query(Long.class).single();
    }

    static void waitUntil(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not reached in 30 s");
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
