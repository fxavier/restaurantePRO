package com.xavier.backend.promotions.loyalty;

import com.xavier.backend.shared.exception.CodedException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Deterministic interleavings for loyalty race tests: latches and {@code pg_blocking_pids}, never sleeps. */
final class Races {

    private Races() {
    }

    /** The result, or the coded refusal, of {@code action} in its own transaction. */
    static Callable<Object> outcome(TransactionTemplate tx, Callable<?> action) {
        return () -> {
            try {
                return tx.execute(status -> {
                    try {
                        return action.call();
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            } catch (CodedException refused) {
                return refused;
            }
        };
    }

    static boolean blockedBy(JdbcClient jdbc, int pid) {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid))").param(pid)
                .query(Long.class).single() > 0;
    }

    /** Polls a condition (not a timing guard): fails the test if it never holds. */
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
