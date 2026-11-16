package com.xavier.backend;

import com.xavier.backend.access.StoreScope;
import com.xavier.backend.access.StoreScopeTestSupport;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantScopes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Test-only, the D171 fixture mechanics under D178 (D189 as amended): production binds the tenant and store scope only
 * at transaction begin ({@code SET LOCAL}), so an autocommit statement carries nothing. Test bodies seed and read
 * with autocommit SQL ({@code jdbc.sql(...)} in a test method), as they did under the D165 checkout hook. This wrapper
 * writes, at every checkout and session-level on the test connection, the thread's resolved tenant and store scope
 * when the thread runs a test body (its fixture fallback included), and {@code ''} (unset, production's state of a
 * pooled connection) on every other thread: MockMvc requests, listeners, schedulers, executors and application
 * startup keep production semantics, so an autocommit statement of production code there still fails closed (A88).
 * Every checkout writes all four settings, so no value of a previous borrower is ever read; a transaction's own
 * {@code SET LOCAL} binding overrides them inside it. Off with {@code restaurantpro.test.fixture-checkouts=false}
 * (the binding suite proves production's pool carries nothing).
 */
final class FixtureCheckouts extends DelegatingDataSource {

    static final String PROPERTY = "restaurantpro.test.fixture-checkouts";

    private static final String WRITE = "SELECT set_config('app.tenant_id', ?, false), "
            + "set_config('app.all_stores', ?, false), set_config('app.store_ids', ?, false), "
            + "set_config('app.scope_origin', ?, false)";

    private final ObjectProvider<TenantScopes> tenants;
    private final ObjectProvider<StoreScopes> stores;

    FixtureCheckouts(DataSource target, ObjectProvider<TenantScopes> tenants, ObjectProvider<StoreScopes> stores) {
        super(target);
        this.tenants = tenants;
        this.stores = stores;
    }

    @Override
    public Connection getConnection() throws SQLException {
        boolean testBody = StoreScopeTestSupport.isTestBody();
        TenantScopes tenantScopes = tenants.getIfAvailable();
        StoreScopes storeScopes = stores.getIfAvailable();
        String tenant = testBody && tenantScopes != null ? tenantScopes.current().map(TenantContext::tenantId)
                .map(Object::toString).orElse("") : "";
        StoreScope scope = testBody && storeScopes != null ? storeScopes.current() : StoreScope.UNSET;
        Connection connection = obtainTargetDataSource().getConnection();
        try (PreparedStatement statement = connection.prepareStatement(WRITE)) {
            statement.setString(1, tenant);
            statement.setString(2, scope.allStoresSetting());
            statement.setString(3, scope.storeIdsLiteral());
            statement.setString(4, scope.origin());
            statement.execute();
            return connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }
}
