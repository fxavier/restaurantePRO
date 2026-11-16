package com.xavier.backend.shared.tenancy;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ACTIVE tenants a reach-ALL job iterates (D185), through {@code tenancy.active_tenant_ids()} (SECURITY DEFINER,
 * ids only): the application role has no privilege on {@code tenancy.*}. In {@code shared} so the scope advisor and
 * {@code TenancyApi} read the one function, also in a module test that does not boot {@code tenancy}.
 */
@Component
public class ActiveTenants {

    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;

    public ActiveTenants(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /** The ACTIVE tenants, ordered by id. */
    public List<UUID> ids() {
        return readOnly.execute(status -> jdbc.sql("SELECT tenancy.active_tenant_ids()").query(UUID.class).list());
    }
}
