package com.xavier.backend.settlement;

import com.xavier.backend.OwnerDatabase;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Owner-role fixtures for tenant tables (D189): the owner bypasses row-level security, but a column default
 * {@code shared.rls_tenant_id()} still raises {@code RS003} with no tenant bound, so a seeding transaction names the
 * tenant its rows belong to, as the backend's own transaction does ({@code SET LOCAL}).
 */
public final class OwnerSeeds {

    private OwnerSeeds() {
    }

    /** {@code work} in one owner transaction whose inserts take {@code tenant}. */
    public static void tx(UUID tenant, Consumer<JdbcClient> work) {
        call(tenant, owner -> {
            work.accept(owner);
            return null;
        });
    }

    /** {@code work} in one owner transaction whose inserts take {@code tenant}, with its result. */
    public static <T> T call(UUID tenant, Function<JdbcClient, T> work) {
        return OwnerDatabase.shared().call(owner -> {
            owner.sql("SELECT set_config('app.tenant_id', ?, true)").param(tenant.toString()).query().listOfRows();
            return work.apply(owner);
        });
    }
}
