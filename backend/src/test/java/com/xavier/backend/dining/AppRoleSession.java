package com.xavier.backend.dining;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.StoreScope;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One connection to the shared test database as {@code restaurantpro_app}, with the D164 store scope written by hand:
 * the "test seam that skips {@code AccessPolicy}" of A71, so the policies of the dining, orders and production
 * suites are seen without any application check in front of them. Not a Spring bean; close it after the test.
 */
public final class AppRoleSession implements AutoCloseable {

    private final Connection connection;
    private final SingleConnectionDataSource dataSource;
    private final JdbcClient jdbc;

    private AppRoleSession(Connection connection) {
        this.connection = connection;
        this.dataSource = new SingleConnectionDataSource(connection, true);
        this.jdbc = JdbcClient.create(dataSource);
    }

    public static AppRoleSession open() throws SQLException {
        return new AppRoleSession(DriverManager.getConnection(TestcontainersConfiguration.postgres().getJdbcUrl(),
                TestcontainersConfiguration.APP_ROLE, TestcontainersConfiguration.appPassword()));
    }

    /** {@code all_stores = off} and exactly {@code stores} (none = an explicit empty scope). */
    public AppRoleSession stores(UUID... stores) {
        return scope("off", new StoreScope(false, Set.of(stores), "test").storeIdsLiteral(), "account:rls-test");
    }

    public AppRoleSession allStores() {
        return scope("on", "{}", "system:rls-test");
    }

    /** Neither setting written: every protected table answers {@code RS001}. */
    public AppRoleSession unset() {
        return scope("", "", "");
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /** {@code work} in one transaction on this connection (for {@code SET LOCAL} before an {@code EXPLAIN}). */
    public <T> T inTransaction(Function<JdbcClient, T> work) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(status -> work.apply(jdbc));
    }

    private AppRoleSession scope(String allStores, String storeIds, String origin) {
        jdbc.sql("SELECT set_config('app.all_stores', ?, false), set_config('app.store_ids', ?, false), "
                + "set_config('app.scope_origin', ?, false)").param(allStores).param(storeIds).param(origin)
                .query().listOfRows();
        return this;
    }

    /** {@code call} fails with the D170 kind of {@code kind}'s SQL state (RS001, 42501 or RS002). */
    public static void assertRefused(ThrowingCallable call, StoreScopeViolations.Kind kind) {
        assertThatThrownBy(call).satisfies(
                (Consumer<Throwable>) error -> assertThat(StoreScopeViolations.classify(error)).contains(kind));
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
