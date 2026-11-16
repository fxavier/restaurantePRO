package com.xavier.backend.inventory;

import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.util.UUID;

/**
 * Worker tasks of the concurrency tests that call services directly, as a listener would: in the store scope of the
 * store under test ({@link StoreScopes#callForStore}) and the bootstrap tenant, never in the JUnit thread's fixture
 * scope (every store). Tasks
 * that go through MockMvc or set an actor need no wrapper: their actor's scope resolves on the worker (ADR 0039).
 */
public final class StoreTasks {

    static final String ORIGIN = "test:worker";

    private StoreTasks() {
    }

    /** In the bootstrap tenant (D185: a listener runs in its event's tenant, every existing suite's, D189). */
    public static Runnable inStore(StoreScopes scopes, UUID store, Runnable task) {
        java.util.concurrent.Callable<Object> inTenant = TenantTestSupport.inBootstrap(() ->
                scopes.callForStore(store, ORIGIN, () -> {
                    task.run();
                    return null;
                }));
        return () -> {
            try {
                inTenant.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
