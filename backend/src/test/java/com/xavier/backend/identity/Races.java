package com.xavier.backend.identity;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deterministic races on real PostgreSQL: the test holds a row lock ({@code lockSql}, one row) while every action
 * starts, waits until all of them are blocked behind it, directly or behind each other ({@code pg_blocking_pids}),
 * then commits and collects the outcomes. No sleeps.
 */
public final class Races {

    private Races() {
    }

    public static <T> List<T> behindLock(PlatformTransactionManager transactions, JdbcClient jdbc, String lockSql,
                                         Object lockParam, List<Callable<T>> actions) {
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        try {
            List<Future<T>> futures = new TransactionTemplate(transactions).execute(tx -> {
                jdbc.sql(lockSql).param(lockParam).query(Object.class).single();
                List<Future<T>> started = actions.stream().map(pool::submit).toList();
                awaitBlocked(jdbc, actions.size());
                return started;
            });
            return futures.stream().map(Races::get).toList();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitBlocked(JdbcClient jdbc, int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND cardinality(pg_blocking_pids(pid)) > 0").query(Long.class).single() < count) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the actions never blocked behind the test's lock");
            }
            Thread.onSpinWait();
        }
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
