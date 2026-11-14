package com.xavier.backend.inventory;

import com.xavier.backend.access.StoreScopes;
import java.util.UUID;

/**
 * Worker tasks of the concurrency tests that call services directly, as a listener would: in the store scope of the
 * store under test ({@link StoreScopes#callForStore}), never in the JUnit thread's fixture scope (every store). Tasks
 * that go through MockMvc or set an actor need no wrapper: their actor's scope resolves on the worker (ADR 0039).
 */
public final class StoreTasks {

    static final String ORIGIN = "test:worker";

    private StoreTasks() {
    }

    public static Runnable inStore(StoreScopes scopes, UUID store, Runnable task) {
        return () -> scopes.callForStore(store, ORIGIN, () -> {
            task.run();
            return null;
        });
    }
}
