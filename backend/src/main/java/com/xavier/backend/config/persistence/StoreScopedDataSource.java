package com.xavier.backend.config.persistence;

import com.xavier.backend.access.StoreScope;
import com.xavier.backend.access.StoreScopes;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * The store-scope hook of D165: every connection handed out carries the thread's store scope, written before the
 * borrower's first statement. One statement sets all three settings unconditionally (session level, in autocommit, so a
 * later rollback cannot revert it), so a stale value left on a pooled connection is never read by its next borrower.
 * A connection whose statement fails is evicted from the pool, never handed out. A pool configured with
 * {@code auto-commit=false} is refused: the settings would then live in the borrower's transaction.
 */
public class StoreScopedDataSource extends DelegatingDataSource {

    static final String SET_SCOPE = "SELECT set_config('app.all_stores', ?, false), "
            + "set_config('app.store_ids', ?, false), set_config('app.scope_origin', ?, false)";

    private static final Logger log = LoggerFactory.getLogger(StoreScopedDataSource.class);

    private final StoreScopes scopes;
    private final StoreScopeEnforcement enforcement;

    public StoreScopedDataSource(DataSource target, StoreScopes scopes, StoreScopeEnforcement enforcement) {
        super(target);
        this.scopes = scopes;
        this.enforcement = enforcement;
    }

    @Override
    public Connection getConnection() throws SQLException {
        // resolved before the checkout: resolving may itself read the session through this pool
        StoreScope scope = effective(scopes.current());
        Connection connection = obtainTargetDataSource().getConnection();
        try {
            if (!connection.getAutoCommit()) {
                connection.close();
                throw new SQLException("Refused a pooled connection with auto-commit off: the store scope must be set "
                        + "outside the borrower's transaction (D165)");
            }
            try (PreparedStatement statement = connection.prepareStatement(SET_SCOPE)) {
                statement.setString(1, scope.allStoresSetting());
                statement.setString(2, scope.storeIdsLiteral());
                statement.setString(3, scope.origin());
                statement.execute();
            }
            return connection;
        } catch (SQLException | RuntimeException e) {
            if (!connection.isClosed()) {
                evict(connection);
            }
            throw e;
        }
    }

    /** One login per pool (D163): a per-call user would bypass the application role. */
    @Override
    public Connection getConnection(String username, String password) {
        throw new UnsupportedOperationException("The pool has one login, the application role (D163)");
    }

    private StoreScope effective(StoreScope scope) {
        return enforcement == StoreScopeEnforcement.PERMISSIVE ? StoreScope.all(scope.origin()) : scope;
    }

    private void evict(Connection connection) {
        try {
            if (obtainTargetDataSource() instanceof HikariDataSource hikari) {
                hikari.evictConnection(connection);
            } else {
                connection.close();
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("Could not evict a connection whose store scope failed", e);
        }
    }
}
