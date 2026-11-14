package com.xavier.backend.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StoreScope;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A73 / D173: a pool of one connection, alternated between actor A, actor B, a system scope and an anonymous call on the
 * same physical connection (same {@code pg_backend_pid()}): each sees exactly its rows, the anonymous call gets
 * {@code RS001}, and a rolled-back transaction does not revert a scope. As the application role, on a scratch table
 * protected by {@code shared.rls_protect_store_table}.
 */
class StoreScopePoolReuseTest {

    static final UUID STORE_A = UUID.fromString("0190a000-0000-7000-8000-0000000000aa");
    static final UUID STORE_B = UUID.fromString("0190a000-0000-7000-8000-0000000000bb");

    static RlsScratch scratch;

    final HikariDataSource pool = StoreScopedDataSourceTest.pool(true);
    final StoreScopes scopes = new StoreScopes(() -> StoreScope.UNSET,
            new StoreScopeViolations(new SimpleMeterRegistry(), Clock.systemUTC()));
    final StoreScopedDataSource hook = new StoreScopedDataSource(pool, scopes, StoreScopeEnforcement.ENFORCE);
    final JdbcClient jdbc = JdbcClient.create(hook);

    @BeforeAll
    static void scratchTable() {
        scratch = RlsScratch.create("pool_reuse");
        scratch.insert(STORE_A, "a");
        scratch.insert(STORE_B, "b");
    }

    @AfterAll
    static void dropScratch() {
        scratch.close();
    }

    @AfterEach
    void close() {
        SecurityContextHolder.clearContext();
        pool.close();
    }

    @Test
    void alternatingScopesOnOneConnectionNeverSeeEachOthersRows() {
        int pid = pid();

        as(Actor.account(UUID.randomUUID(), Set.of(Permission.ORDER_VIEW.authority(STORE_A))));
        assertThat(labels()).containsExactly("a");
        as(Actor.employee(UUID.randomUUID(), UUID.randomUUID(), STORE_B, Set.of(Permission.ORDER_EDIT.authority())));
        assertThat(labels()).containsExactly("b");
        SecurityContextHolder.clearContext();
        assertThat(scopes.call(SystemScope.STOCK_RECONCILE, this::labels)).containsExactly("a", "b");
        assertThat(scopes.call(SystemScope.HOUSEKEEPING, this::labels)).as("an explicit empty scope: no error")
                .isEmpty();
        assertThatThrownBy(this::labels).as("anonymous: fail closed")
                .hasMessageContaining("store scope not set (origin none)")
                .satisfies(error -> assertThat(StoreScopeViolations.classify(error))
                        .contains(StoreScopeViolations.Kind.SCOPE_MISSING));
        as(Actor.account(UUID.randomUUID(), Set.of(Permission.ORDER_VIEW.authority(STORE_A))));
        assertThat(labels()).containsExactly("a");

        assertThat(pid()).as("one physical connection throughout").isEqualTo(pid);
    }

    @Test
    void aRolledBackTransactionDoesNotRevertTheScope() throws SQLException {
        as(Actor.account(UUID.randomUUID(), Set.of(Permission.ORDER_VIEW.authority(STORE_A))));
        try (Connection connection = hook.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE " + scratch.table() + " SET label = label");
            }
            connection.rollback();
            connection.setAutoCommit(true);

            try (ResultSet row = connection.createStatement().executeQuery("SELECT label FROM " + scratch.table())) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("a");
                assertThat(row.next()).as("still store A's scope after the rollback").isFalse();
            }
        }
    }

    @Test
    void theScopeCannotChangeInsideATransaction() {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(hook));

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                scopes.run(SystemScope.ALERTS, () -> jdbc.sql("SELECT 1").query(Integer.class).single())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("inside a transaction");
    }

    private List<String> labels() {
        return jdbc.sql("SELECT label FROM " + scratch.table() + " ORDER BY label").query(String.class).list();
    }

    private int pid() {
        return scopes.call(SystemScope.HOUSEKEEPING, () -> jdbc.sql("SELECT pg_backend_pid()").query(Integer.class)
                .single());
    }

    private static void as(Actor actor) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
    }
}
