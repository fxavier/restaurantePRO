package com.xavier.backend.settlement;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The S67 test seam of A71 (D171): one connection as the application role whose store scope the test writes itself,
 * the three settings the checkout hook writes (D164, D165), so a statement reaches the policies with no
 * {@code AccessPolicy} in front of it. Also reads a schema's D167 classification from the catalog. Used by the
 * settlement, cash and fiscal row-level security suites.
 */
public final class AppRoleSession implements AutoCloseable {

    private static final String ORIGIN = "test:s67-seam";

    private final Connection connection;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    private AppRoleSession(Connection connection) {
        this.connection = connection;
        SingleConnectionDataSource single = new SingleConnectionDataSource(connection, true);
        this.jdbc = JdbcClient.create(single);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(single));
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

    /** The scope of an actor holding {@code stores} (none = an explicit empty scope). */
    public AppRoleSession stores(UUID... stores) {
        String ids = Arrays.stream(stores).map(UUID::toString).sorted().collect(Collectors.joining(",", "{", "}"));
        return scope("off", ids);
    }

    /** The scope of an installation-wide account or an ALL system scope. */
    public AppRoleSession allStores() {
        return scope("on", "{}");
    }

    /** No scope at all: a protected table then raises {@code RS001}. */
    public AppRoleSession unset() {
        return scope("", "");
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /** {@code work} in one transaction on this connection, so deferred constraint triggers run at its commit. */
    public void inTransaction(Runnable work) {
        tx.executeWithoutResult(status -> work.run());
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

    /**
     * Each table of {@code schema} → its D167 class as built: {@code STORE}, {@code STORE_OR_ALL},
     * {@code CHILD:<parent>} (read from its {@code parent_fixed} trigger's foreign key), {@code UNSCOPED}, or
     * {@code NONE} when it has neither a policy nor a registration.
     */
    public static Map<String, String> classification(String schema) {
        List<Map<String, Object>> rows = OwnerDatabase.shared().jdbc().sql("""
                        SELECT c.relname AS name,
                            CASE WHEN u.table_name IS NOT NULL THEN 'UNSCOPED'
                                 WHEN p.polname = 'store_scope' THEN 'STORE'
                                 WHEN p.polname = 'store_or_all_scope' THEN 'STORE_OR_ALL'
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

    private AppRoleSession scope(String allStores, String storeIds) {
        jdbc.sql("SELECT set_config('app.all_stores', ?, false), set_config('app.store_ids', ?, false), "
                + "set_config('app.scope_origin', ?, false)").param(allStores).param(storeIds).param(ORIGIN)
                .query().listOfRows();
        return this;
    }
}
