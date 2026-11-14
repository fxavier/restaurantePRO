package com.xavier.backend;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Privileged fixtures on the shared test database as {@code restaurantpro_owner} (D171): {@code TRUNCATE},
 * {@code ALTER TABLE … DISABLE TRIGGER}, {@code ANALYZE}, DDL, {@code DROP}. The owner bypasses row-level security.
 * One two-connection pool for the whole JVM; a bean of every context that imports {@link TestcontainersConfiguration},
 * and {@link #shared()} for code outside Spring. Production code never runs here: it runs as the application role.
 */
public final class OwnerDatabase {

    private static final int POOL_SIZE = 2;
    private static volatile OwnerDatabase shared;

    private final HikariDataSource dataSource;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    private OwnerDatabase(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = JdbcClient.create(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** The JVM's owner connection to the shared container. */
    public static OwnerDatabase shared() {
        OwnerDatabase current = shared;
        if (current == null) {
            synchronized (OwnerDatabase.class) {
                if (shared == null) {
                    var postgres = TestcontainersConfiguration.postgres();
                    HikariConfig config = new HikariConfig();
                    config.setJdbcUrl(postgres.getJdbcUrl());
                    config.setUsername(TestcontainersConfiguration.OWNER_ROLE);
                    config.setPassword(TestcontainersConfiguration.ownerPassword());
                    config.setMaximumPoolSize(POOL_SIZE);
                    config.setMinimumIdle(0);
                    config.setPoolName("owner-fixtures");
                    shared = new OwnerDatabase(new HikariDataSource(config));
                }
                current = shared;
            }
        }
        return current;
    }

    /** Autocommit statements as the owner. */
    public JdbcClient jdbc() {
        return jdbc;
    }

    /** {@code work} in one owner transaction (committed unless it throws). */
    public void tx(Consumer<JdbcClient> work) {
        tx.executeWithoutResult(status -> work.accept(jdbc));
    }

    /** {@code work} in one owner transaction, with its result. */
    public <T> T call(Function<JdbcClient, T> work) {
        return tx.execute(status -> work.apply(jdbc));
    }

    /**
     * {@code work} in one owner transaction that is always rolled back: for proving a guard refuses the owner (a
     * {@code TRUNCATE} an immutability trigger must stop), so a broken guard still changes nothing.
     */
    public void rolledBack(Consumer<JdbcClient> work) {
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            work.accept(jdbc);
        });
    }

    /** The owner pool, for tools that need a {@link DataSource} (Flyway, a plain JDBC connection). */
    public DataSource dataSource() {
        return dataSource;
    }
}
