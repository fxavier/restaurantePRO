package com.xavier.backend.inventory.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Latch and lock-wait helpers for the deterministic concurrency tests (no sleeps as guards). */
final class Concurrency {

    private Concurrency() {
    }

    static int backendPid(JdbcClient jdbc) {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    /** Whether some session waits on a lock held by backend {@code pid}. */
    static boolean someoneBlockedBy(JdbcClient jdbc, int pid) {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid))").param(pid)
                .query(Long.class).single() > 0;
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
