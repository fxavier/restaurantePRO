package com.xavier.backend.inventory.purchasing.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Latch and lock-wait helpers for the deterministic concurrency tests (no sleeps as guards). */
final class Locks {

    private Locks() {
    }

    /**
     * How many sessions wait, directly or queued behind another waiter, for a lock while running a statement on
     * {@code table}. Row-lock waiters queue: only the first waits on the holder, the next ones on the first.
     */
    static long blockedOn(JdbcClient jdbc, String table) {
        return jdbc.sql("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE cardinality(pg_blocking_pids(pid)) > 0 AND query ILIKE '%' || ? || '%'""")
                .param(table).query(Long.class).single();
    }

    /** Polls a condition (not a timing guard): fails the test if it never holds. */
    static void waitUntil(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition not reached in 60 s");
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new AssertionError("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
