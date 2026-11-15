package com.xavier.backend.config.persistence;

import com.xavier.backend.access.StoreScope;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantScopes;
import jakarta.persistence.EntityTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * D178 (as amended): the only {@code PlatformTransactionManager}. Every transaction, JPA, {@code JdbcClient},
 * {@code TransactionTemplate}, Spring Session and the event publication registry alike, is bound at begin to the
 * thread's tenant ({@link TenantScopes}) and store scope ({@link StoreScopes}) with one statement on the transaction's
 * own connection:
 *
 * <pre>{@code SELECT set_config('app.tenant_id', ?, true), set_config('app.all_stores', ?, true),
 *        set_config('app.store_ids', ?, true), set_config('app.scope_origin', ?, true)}</pre>
 *
 * <p>{@code set_config(..., true)} is {@code SET LOCAL} with bind parameters: the values end with the transaction, so a
 * pooled connection carries nothing afterwards (also behind PgBouncer in transaction mode). With no tenant the
 * transaction binds {@code ''} (unset): a protected table then raises {@code RS003}; only PLATFORM tables are reached
 * that way (the event registry's own transactions, the startup checks, leases). If the statement fails, the
 * transaction is rolled back and released and {@link CannotCreateTransactionException} is thrown: no statement of the
 * caller ever runs unbound. Replaces the D165 checkout hook ({@code StoreScopedDataSource}).
 *
 * <p>D170: with {@link StoreScopeEnforcement#PERMISSIVE} the store scope is all stores; the tenant is never relaxed.
 */
public class TenantBindingTransactionManager extends JpaTransactionManager {

    static final String BIND = "SELECT set_config('app.tenant_id', ?, true), set_config('app.all_stores', ?, true), "
            + "set_config('app.store_ids', ?, true), set_config('app.scope_origin', ?, true)";

    private final transient ObjectProvider<TenantScopes> tenants;
    private final transient ObjectProvider<StoreScopes> stores;
    private final StoreScopeEnforcement enforcement;

    public TenantBindingTransactionManager(ObjectProvider<TenantScopes> tenants, ObjectProvider<StoreScopes> stores,
                                           StoreScopeEnforcement enforcement) {
        this.tenants = tenants;
        this.stores = stores;
        this.enforcement = enforcement;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        try {
            DataSource dataSource = getDataSource();
            if (dataSource == null) {
                throw new IllegalStateException("The EntityManagerFactory exposes no DataSource to bind");
            }
            bind(DataSourceUtils.getConnection(dataSource));
        } catch (RuntimeException | SQLException | Error e) {
            abandon(transaction);
            throw new CannotCreateTransactionException("Could not bind the transaction's tenant and store scope "
                    + "(D178); nothing ran", e);
        }
    }

    /** Writes the four settings into {@code connection}, the transaction's own. */
    protected void bind(Connection connection) throws SQLException {
        TenantScopes tenantScopes = tenants.getIfAvailable();
        StoreScopes storeScopes = stores.getIfAvailable();
        String tenant = tenantScopes == null ? "" : tenantScopes.current().map(TenantContext::tenantId)
                .map(Object::toString).orElse("");
        StoreScope scope = storeScopes == null ? StoreScope.UNSET : effective(storeScopes.current());
        try (PreparedStatement statement = connection.prepareStatement(BIND)) {
            statement.setString(1, tenant);
            statement.setString(2, scope.allStoresSetting());
            statement.setString(3, scope.storeIdsLiteral());
            statement.setString(4, scope.origin());
            statement.execute();
        }
    }

    private StoreScope effective(StoreScope scope) {
        return enforcement == StoreScopeEnforcement.PERMISSIVE ? StoreScope.all(scope.origin()) : scope;
    }

    /** Rolls back the transaction {@code super.doBegin} started and releases what it bound to the thread. */
    private void abandon(Object transaction) {
        try {
            Object holder = TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
            if (holder instanceof EntityManagerHolder entityManager) {
                EntityTransaction begun = entityManager.getEntityManager().getTransaction();
                if (begun.isActive()) {
                    begun.rollback();
                }
            }
        } catch (RuntimeException rollbackFailure) {
            logger.warn("Could not roll back a transaction whose binding failed", rollbackFailure);
        } finally {
            doCleanupAfterCompletion(transaction);
        }
    }
}
