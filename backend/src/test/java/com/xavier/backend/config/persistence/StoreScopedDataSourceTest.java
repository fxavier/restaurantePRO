package com.xavier.backend.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.StoreScope;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** D165 and D173: what the checkout hook writes, and what it does when it cannot. As the application role. */
class StoreScopedDataSourceTest {

    static final UUID STORE = UUID.fromString("0190a000-0000-7000-8000-0000000000aa");

    final AtomicReference<StoreScope> scope = new AtomicReference<>(StoreScope.UNSET);
    final StoreScopes scopes = new StoreScopes(scope::get,
            new StoreScopeViolations(new SimpleMeterRegistry(), Clock.systemUTC()));
    final HikariDataSource pool = pool(true);

    @AfterEach
    void close() {
        pool.close();
    }

    @Test
    void everyCheckoutWritesAllThreeSettings() throws SQLException {
        StoreScopedDataSource hook = new StoreScopedDataSource(pool, scopes, StoreScopeEnforcement.ENFORCE);

        scope.set(StoreScope.all("system:ALERTS"));
        assertThat(settingsOf(hook)).containsExactly("on", "{}", "system:ALERTS");
        scope.set(StoreScope.ofStore(STORE, "listener:X.on"));
        assertThat(settingsOf(hook)).containsExactly("off", "{" + STORE + "}", "listener:X.on");
        scope.set(StoreScope.UNSET);
        assertThat(settingsOf(hook)).as("unset overwrites the previous borrower's values").containsExactly("", "",
                "none");
    }

    @Test
    void aFailingScopeStatementEvictsTheConnection() throws SQLException {
        StoreScopedDataSource hook = new StoreScopedDataSource(pool, scopes, StoreScopeEnforcement.ENFORCE);
        scope.set(StoreScope.all("good"));
        int before = pidOf(hook);

        scope.set(StoreScope.all("bad\u0000origin")); // PostgreSQL refuses a NUL in text: the statement fails
        assertThatThrownBy(hook::getConnection).isInstanceOf(SQLException.class);

        scope.set(StoreScope.all("good"));
        assertThat(pidOf(hook)).as("the failed connection was evicted, never handed out").isNotEqualTo(before);
    }

    @Test
    void aScopeThatCannotBeResolvedTakesNoConnection() throws SQLException {
        StoreScopes failing = new StoreScopes(() -> {
            throw new IllegalStateException("the session could not be read");
        }, new StoreScopeViolations(new SimpleMeterRegistry(), Clock.systemUTC()));
        StoreScopedDataSource hook = new StoreScopedDataSource(pool, failing, StoreScopeEnforcement.ENFORCE);

        assertThatThrownBy(hook::getConnection).isInstanceOf(IllegalStateException.class);

        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).as("nothing leaked").isZero();
    }

    @Test
    void aPoolWithAutoCommitOffIsRefused() {
        try (HikariDataSource manual = pool(false)) {
            StoreScopedDataSource hook = new StoreScopedDataSource(manual, scopes, StoreScopeEnforcement.ENFORCE);

            assertThatThrownBy(hook::getConnection).isInstanceOf(SQLException.class).hasMessageContaining("auto-commit");
        }
    }

    @Test
    void aPerCallLoginIsRefused() {
        StoreScopedDataSource hook = new StoreScopedDataSource(pool, scopes, StoreScopeEnforcement.ENFORCE);

        assertThatThrownBy(() -> hook.getConnection(TestcontainersConfiguration.postgres().getUsername(), "x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void permissiveModeWritesAllStoresAndKeepsTheOrigin() throws SQLException {
        StoreScopedDataSource hook = new StoreScopedDataSource(pool, scopes, StoreScopeEnforcement.PERMISSIVE);
        scope.set(StoreScope.ofStore(STORE, "account:1"));

        assertThat(settingsOf(hook)).containsExactly("on", "{}", "account:1");
    }

    private static List<String> settingsOf(StoreScopedDataSource hook) throws SQLException {
        try (Connection connection = hook.getConnection();
             ResultSet row = connection.createStatement().executeQuery("SELECT current_setting('app.all_stores', true),"
                     + " current_setting('app.store_ids', true), current_setting('app.scope_origin', true)")) {
            row.next();
            return List.of(row.getString(1), row.getString(2), row.getString(3));
        }
    }

    private static int pidOf(StoreScopedDataSource hook) throws SQLException {
        try (Connection connection = hook.getConnection();
             ResultSet row = connection.createStatement().executeQuery("SELECT pg_backend_pid()")) {
            row.next();
            return row.getInt(1);
        }
    }

    static HikariDataSource pool(boolean autoCommit) {
        var postgres = TestcontainersConfiguration.postgres();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(TestcontainersConfiguration.APP_ROLE);
        config.setPassword(TestcontainersConfiguration.appPassword());
        config.setMaximumPoolSize(1);
        config.setAutoCommit(autoCommit);
        config.setPoolName("store-scope-test");
        return new HikariDataSource(config);
    }
}
