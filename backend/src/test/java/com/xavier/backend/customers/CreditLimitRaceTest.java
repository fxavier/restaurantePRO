package com.xavier.backend.customers;

import static com.xavier.backend.customers.CustomersFixtures.mzn;
import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.customers.exception.CustomerRuleException;
import java.math.BigDecimal;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two terminals cannot put the same customer over the limit (spec §8.4 "reserva transacional do limite", D73, D91,
 * A20 reserve half): {@code reserve} takes {@code credit_account FOR UPDATE} and decides under it. Limit 100, two
 * charges of 60 on different orders → exactly one reservation.
 */
@CustomersModuleTest
class CreditLimitRaceTest {

    @Autowired
    CustomersApi customers;

    @Autowired
    CustomersFixtures fixtures;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    UUID customer;

    @BeforeEach
    void aCustomerWithALimitOf100() throws Exception {
        customer = fixtures.creditCustomer("100.00");
    }

    /**
     * Deterministic interleaving: A has reserved and holds its transaction open; B must then wait on A's row lock
     * (seen in {@code pg_blocking_pids}), and once A commits B sees A's reservation and is refused.
     */
    @Test
    void theSecondTerminalWaitsForTheFirstAndThenSeesTheLimitTaken() throws Exception {
        CountDownLatch aReserved = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);
        AtomicInteger aBackend = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ReservationView> a = pool.submit(() -> tx.execute(status -> {
                ReservationView reserved = customers.reserve(customer, mzn("60.00"), UUID.randomUUID());
                aBackend.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                aReserved.countDown();
                await(aMayCommit);
                return reserved;
            }));
            assertThat(aReserved.await(30, TimeUnit.SECONDS)).isTrue();

            Future<Object> b = pool.submit(outcome(() -> customers.reserve(customer, mzn("60.00"), UUID.randomUUID())));
            waitUntil(() -> b.isDone() || blockedBy(aBackend.get()));

            assertThat(b.isDone()).as("B must wait for A's lock on credit_account, not decide on its own").isFalse();
            aMayCommit.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS).status()).isEqualTo("ACTIVE");
            assertThat(b.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(CustomerRuleException.class, refused -> {
                assertThat(refused.code()).isEqualTo("CREDIT_LIMIT_EXCEEDED");
                assertThat(refused.properties()).containsEntry("available", new BigDecimal("40.00"));
            });
        }
        assertThat(activeReservations()).isEqualTo(1);
    }

    @Test
    void twoReservationsStartedTogetherNeverBothSucceed() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Object> reserve = outcome(() -> customers.reserve(customer, mzn("60.00"), UUID.randomUUID()));
            Future<Object> first = pool.submit(() -> {
                await(start);
                return reserve.call();
            });
            Future<Object> second = pool.submit(() -> {
                await(start);
                return reserve.call();
            });
            start.countDown();

            List<Object> outcomes = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(ReservationView.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(CustomerRuleException.class::isInstance).hasSize(1);
        }
        assertThat(activeReservations()).isEqualTo(1);
        assertThat(customers.credit(customer).available()).isEqualTo(mzn("40.00"));
    }

    /** The reservation, or the business refusal, of one transaction. */
    private Callable<Object> outcome(Callable<ReservationView> reserve) {
        return () -> {
            try {
                return tx.execute(status -> {
                    try {
                        return reserve.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            } catch (CustomerRuleException refused) {
                return refused;
            }
        };
    }

    private boolean blockedBy(int pid) {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid))").param(pid)
                .query(Long.class).single() > 0;
    }

    private long activeReservations() {
        return jdbc.sql("SELECT count(*) FROM customers.credit_reservation WHERE customer_id = ? AND status = 'ACTIVE'")
                .param(customer).query(Long.class).single();
    }

    /** Polls a condition (not a timing guard): fails the test if it never holds. */
    private static void waitUntil(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not reached in 30 s");
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
    }

    private static void await(CountDownLatch latch) {
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
