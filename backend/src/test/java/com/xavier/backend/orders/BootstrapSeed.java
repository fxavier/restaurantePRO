package com.xavier.backend.orders;

import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.shared.tenancy.BootstrapTenant;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.Properties;
import java.util.concurrent.Callable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Autocommit seeding of the orders, dining and production tables for suites that run in the bootstrap tenant (D189):
 * a connection as the application role whose session carries the bootstrap tenant and every store, as the fixture
 * fallback gives a test thread. Since S74a the {@code tenant_id} column defaults to the bound tenant, so a plain owner
 * insert (no tenant bound) raises {@code RS003}; this is the one place that binds it for a raw seed. Not for the
 * isolation suites (they bind tenants A and B themselves, {@code AppRoleSession}).
 */
public final class BootstrapSeed {

    private BootstrapSeed() {
    }

    /** The bootstrap tenant for the rest of the owner transaction {@code owner} runs in (the owner's own seeding). */
    public static void bind(JdbcClient owner) {
        owner.sql("SELECT set_config('app.tenant_id', ?, true)").param(BootstrapTenant.ID.toString()).query()
                .listOfRows();
    }

    /** {@code tenant} for the rest of the owner transaction {@code owner} runs in (a second tenant's rows). */
    public static void bind(JdbcClient owner, java.util.UUID tenant) {
        owner.sql("SELECT set_config('app.tenant_id', ?, true)").param(tenant.toString()).query().listOfRows();
    }

    /** {@code task} on another thread as a request of the bootstrap tenant (no fixture fallback reaches a pool). */
    public static Runnable inBootstrap(Runnable task) {
        Callable<Object> bound = TenantTestSupport.inBootstrap(() -> {
            task.run();
            return null;
        });
        return () -> {
            try {
                bound.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    /** {@code task} on another thread as a request of the bootstrap tenant, with its result. */
    public static <T> java.util.function.Supplier<T> supplyInBootstrap(java.util.function.Supplier<T> task) {
        Callable<T> bound = TenantTestSupport.inBootstrap(task::get);
        return () -> {
            try {
                return bound.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    public static JdbcClient jdbc() {
        Properties settings = new Properties();
        settings.setProperty("options", "-c app.tenant_id=" + BootstrapTenant.ID
                + " -c app.all_stores=on -c app.store_ids={} -c app.scope_origin=system:seed");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                TestcontainersConfiguration.postgres().getJdbcUrl(), TestcontainersConfiguration.APP_ROLE,
                TestcontainersConfiguration.appPassword());
        dataSource.setConnectionProperties(settings);
        return JdbcClient.create(dataSource);
    }
}
