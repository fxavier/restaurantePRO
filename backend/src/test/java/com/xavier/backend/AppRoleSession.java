package com.xavier.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.access.StoreScope;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The test seam of A71 (D171): one connection to the shared test database as {@code restaurantpro_app}, whose D164
 * store scope the test writes by hand (the three settings the checkout hook writes, D165), so a statement reaches the
 * policies with no {@code AccessPolicy} in front of it. Also reads a schema's D167 classification from the catalog.
 * Used by the row-level security and plan suites of every policy module. Not a Spring bean; close it after the test.
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

    /** A new connection as {@code restaurantpro_app}, its scope unset. */
    public static AppRoleSession open() {
        try {
            return new AppRoleSession(DriverManager.getConnection(TestcontainersConfiguration.postgres().getJdbcUrl(),
                    TestcontainersConfiguration.APP_ROLE, TestcontainersConfiguration.appPassword()));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code all_stores = off} and exactly {@code stores} (none = an explicit empty scope). */
    public AppRoleSession stores(UUID... stores) {
        return scope("off", new StoreScope(false, Set.of(stores), "test").storeIdsLiteral(), "account:rls-test");
    }

    /** The scope of an installation-wide account or an ALL system scope. */
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

    /** {@code work} in one transaction on this connection, so deferred constraint triggers run at its commit. */
    public void inTransaction(Runnable work) {
        transactions().executeWithoutResult(status -> work.run());
    }

    /** {@code work} in one transaction on this connection (for {@code SET LOCAL} before an {@code EXPLAIN}). */
    public <T> T inTransaction(Function<JdbcClient, T> work) {
        return transactions().execute(status -> work.apply(jdbc));
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The SQL state of the first {@link SQLException} in the cause chain, or {@code null}. */
    public static String sqlState(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** {@code call} fails with the D170 kind of {@code kind}'s SQL state (RS001, 42501 or RS002). */
    public static void assertRefused(ThrowingCallable call, StoreScopeViolations.Kind kind) {
        assertThatThrownBy(call).satisfies(
                (Consumer<Throwable>) error -> assertThat(StoreScopeViolations.classify(error)).contains(kind));
    }

    /**
     * Each table of {@code schema} → its D167 class as built: {@code STORE}, {@code STORE_OR_ALL},
     * {@code CHILD:<parent>} (read from its {@code parent_fixed} trigger's foreign key), {@code UNSCOPED}, or
     * {@code NONE} when it has neither a policy nor a registration.
     */
    public static Map<String, String> classification(String schema) {
        List<Map<String, Object>> rows = OwnerDatabase.shared().jdbc().sql("""
                        SELECT DISTINCT c.relname AS name,
                            CASE WHEN u.table_name IS NOT NULL THEN 'UNSCOPED'
                                 WHEN p.polname = 'store_scope' THEN 'STORE'
                                 WHEN p.polname LIKE 'store\\_or\\_all\\_parent\\_%' THEN 'STORE_OR_ALL_CHILD'
                                 WHEN p.polname LIKE 'store\\_or\\_all\\_%' THEN 'STORE_OR_ALL'
                                 WHEN p.polname = 'parent_scope' THEN 'CHILD:' || coalesce((
                                     SELECT k.confrelid::regclass::text FROM pg_catalog.pg_trigger t
                                         JOIN pg_catalog.pg_constraint k ON k.conrelid = t.tgrelid
                                             AND k.contype = 'f' AND k.conkey = ARRAY[t.tgattr[0]]
                                     WHERE t.tgrelid = c.oid AND t.tgname = 'parent_fixed'), '?')
                                 WHEN p.polname IS NULL THEN 'NONE'
                                 ELSE 'OTHER:' || p.polname END AS class
                        FROM pg_catalog.pg_class c
                            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                            LEFT JOIN shared.rls_unscoped_table u ON u.table_name = c.oid
                            LEFT JOIN pg_catalog.pg_policy p ON p.polrelid = c.oid
                        WHERE n.nspname = ? AND c.relkind IN ('r', 'p')""")
                .param(schema).query().listOfRows();
        return rows.stream().collect(Collectors.toMap(row -> (String) row.get("name"), row -> (String) row.get("class"),
                (first, second) -> first + "+" + second));
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private AppRoleSession scope(String allStores, String storeIds, String origin) {
        jdbc.sql("SELECT set_config('app.all_stores', ?, false), set_config('app.store_ids', ?, false), "
                + "set_config('app.scope_origin', ?, false)").param(allStores).param(storeIds).param(origin)
                .query().listOfRows();
        return this;
    }
}
