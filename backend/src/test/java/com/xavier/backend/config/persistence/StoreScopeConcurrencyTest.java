package com.xavier.backend.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StoreScope;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D173: a listener for store A and a request for store B write at the same moment (both transactions open together,
 * a barrier, no sleeps), on one pool: each writes its own store and is refused the other's ({@code 42501}).
 */
class StoreScopeConcurrencyTest {

    static final UUID STORE_A = UUID.fromString("0190a000-0000-7000-8000-0000000000aa");
    static final UUID STORE_B = UUID.fromString("0190a000-0000-7000-8000-0000000000bb");

    static RlsScratch scratch;

    @BeforeAll
    static void scratchTable() {
        scratch = RlsScratch.create("concurrency");
    }

    @AfterAll
    static void dropScratch() {
        scratch.close();
    }

    @Test
    void aListenerOfAAndARequestOfBEachWriteOnlyTheirStore() throws Exception {
        StoreScopes scopes = new StoreScopes(() -> StoreScope.UNSET,
                new StoreScopeViolations(new SimpleMeterRegistry(), Clock.systemUTC()));
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (HikariDataSource pool = pool(2)) {
            StoreScopedDataSource hook = new StoreScopedDataSource(pool, scopes, StoreScopeEnforcement.ENFORCE);
            JdbcClient jdbc = JdbcClient.create(hook);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(hook));
            CyclicBarrier bothOpen = new CyclicBarrier(2);

            CompletableFuture<String> listener = CompletableFuture.supplyAsync(() -> scopes.callForStore(STORE_A,
                    "listener:Probe.on", () -> writeOwnThenOther(tx, jdbc, bothOpen, STORE_A, STORE_B)), threads);
            CompletableFuture<String> request = CompletableFuture.supplyAsync(() -> {
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        Actor.employee(UUID.randomUUID(), UUID.randomUUID(), STORE_B,
                                Set.of(Permission.ORDER_EDIT.authority(STORE_B))), null, List.of()));
                try {
                    return writeOwnThenOther(tx, jdbc, bothOpen, STORE_B, STORE_A);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }, threads);

            assertThat(listener.get(30, TimeUnit.SECONDS)).isEqualTo("refused");
            assertThat(request.get(30, TimeUnit.SECONDS)).isEqualTo("refused");
        } finally {
            threads.shutdownNow();
        }
        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT store_id FROM " + scratch.table() + " ORDER BY label")
                .query(UUID.class).list()).containsExactly(STORE_A, STORE_B);
    }

    /** Inserts a row of {@code own} (labelled by it), waits until the other side has too, then tries {@code other}. */
    private static String writeOwnThenOther(TransactionTemplate tx, JdbcClient jdbc, CyclicBarrier bothOpen,
                                            UUID own, UUID other) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO " + scratch.table() + " (id, store_id, label) VALUES (?, ?, ?)")
                    .param(UUID.randomUUID()).param(own).param(own == STORE_A ? "a" : "b").update();
            await(bothOpen);
        });
        try {
            jdbc.sql("INSERT INTO " + scratch.table() + " (id, store_id, label) VALUES (?, ?, 'x')")
                    .param(UUID.randomUUID()).param(other).update();
            return "written";
        } catch (RuntimeException e) {
            assertThat(StoreScopeViolations.classify(e)).contains(StoreScopeViolations.Kind.WRITE_REFUSED);
            return "refused";
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("the other side never opened its transaction", e);
        }
    }

    private static HikariDataSource pool(int size) {
        HikariDataSource pool = StoreScopedDataSourceTest.pool(true);
        pool.setMaximumPoolSize(size);
        return pool;
    }
}
