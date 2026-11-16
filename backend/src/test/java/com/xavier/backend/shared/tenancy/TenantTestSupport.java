package com.xavier.backend.shared.tenancy;

import com.xavier.backend.OwnerDatabase;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The tenant harness of D189 (as amended). Existing tests run in the bootstrap tenant: on a marked JUnit thread with
 * no explicit binding and no tenant-bound actor, {@link #FALLBACK} answers {@link #BOOTSTRAP} (the D171 mechanics; the
 * marks are set by {@code StoreScopeFixtureExtension}, unset for a {@link StrictTenantScope} class and during an
 * application's startup and shutdown). Every other thread gets production's unbound tenant. The isolation suites use
 * tenants {@link #A} and {@link #B}, created with identical codes by each suite; they are PROVISIONING (bound by
 * tests, never iterated by reach-ALL jobs), so jobs of other tests keep running for the bootstrap tenant only.
 */
public final class TenantTestSupport {

    /** The bootstrap tenant: the literal of the migrations. */
    public static final UUID BOOTSTRAP = BootstrapTenant.ID;
    public static final UUID A = UUID.fromString("0190b000-0000-7000-8000-00000000000a");
    public static final UUID B = UUID.fromString("0190b000-0000-7000-8000-00000000000b");

    public static final String ORIGIN = "test-fixture";

    private static volatile boolean strictClassRunning;

    /**
     * The test context's {@link TenantFallback}: the bootstrap tenant on a marked JUnit thread, and on a thread a test
     * runs as an authenticated actor (a request it emulates off MockMvc, e.g. a race between two workers: in
     * production every request runs in its tenant, the pre-resolution one until S72), unless the running test class
     * is {@link StrictTenantScope}. Every other thread (listeners, schedulers, executors) has none.
     */
    public static final TenantFallback FALLBACK = () -> {
        if (isFixtureThread()) {
            return Optional.of(new TenantContext(BOOTSTRAP, MembershipKind.SYSTEM, null, ORIGIN));
        }
        if (!strictClassRunning && actsAsAnActor()) {
            return Optional.of(new TenantContext(BOOTSTRAP, MembershipKind.SYSTEM, null, "test-actor"));
        }
        return Optional.empty();
    };

    private static final ThreadLocal<Deque<Boolean>> MARKS = ThreadLocal.withInitial(ArrayDeque::new);

    private TenantTestSupport() {
    }

    /** The thread's bindings (static), reachable from a worker that has no Spring bean at hand. */
    private static final TenantScopes BINDINGS = new TenantScopes((com.xavier.backend.access.StoreScopes) null,
            Optional::empty);

    /**
     * A worker task emulating a request or a listener of an existing suite: it runs in the bootstrap tenant (in
     * production every request and listener runs in its tenant, D177/D185); its store scope stays the task's own.
     */
    public static <T> java.util.concurrent.Callable<T> inBootstrap(java.util.concurrent.Callable<T> task) {
        return () -> {
            Object[] result = new Object[1];
            Exception[] failure = new Exception[1];
            BINDINGS.callIn(new TenantContext(BOOTSTRAP, MembershipKind.SYSTEM, null, "test:worker"), () -> {
                try {
                    result[0] = task.call();
                } catch (Exception e) {
                    failure[0] = e;
                }
                return null;
            });
            if (failure[0] != null) {
                throw failure[0];
            }
            @SuppressWarnings("unchecked")
            T value = (T) result[0];
            return value;
        };
    }

    /** Tenants A and B exist (PROVISIONING), as the owner; idempotent. */
    public static void ensureTenants() {
        ensureTenant(A, "tenant-a", "PROVISIONING");
        ensureTenant(B, "tenant-b", "PROVISIONING");
    }

    /** A tenant with {@code id}, {@code code} and {@code status}, created or set as the owner (tenancy is PLATFORM). */
    public static void ensureTenant(UUID id, String code, String status) {
        OwnerDatabase.shared().jdbc().sql("""
                        INSERT INTO tenancy.tenant (id, code, legal_name, status, created_at, closed_at)
                        VALUES (:id, :code, :code, :status, now(), CASE WHEN :status = 'CLOSED' THEN now() END)
                        ON CONFLICT (id) DO UPDATE SET status = excluded.status, closed_at = excluded.closed_at""")
                .param("id", id).param("code", code).param("status", status).update();
    }

    /** {@code action} in {@code tenantId} (the thread's store scope kept), as a resolved membership would bind it. */
    public static <T> T inTenant(TenantScopes scopes, UUID tenantId, Supplier<T> action) {
        return scopes.callIn(new TenantContext(tenantId, MembershipKind.SYSTEM, null, "test:" + tenantId), action);
    }

    /** {@link #inTenant} with no result. */
    public static void inTenant(TenantScopes scopes, UUID tenantId, Runnable action) {
        inTenant(scopes, tenantId, () -> {
            action.run();
            return null;
        });
    }

    /** Whether this thread runs fixture code of a test that is not {@link StrictTenantScope}. */
    public static boolean isFixtureThread() {
        Boolean innermost = MARKS.get().peek();
        return innermost != null && innermost;
    }

    public static void enter(boolean fixture) {
        MARKS.get().push(fixture);
    }

    /** Set by {@code StoreScopeFixtureExtension} around each test of a {@link StrictTenantScope} class. */
    public static void strictClass(boolean strict) {
        strictClassRunning = strict;
    }

    private static boolean actsAsAnActor() {
        return CurrentAuthentication.read().map(authentication -> authentication.getPrincipal()
                instanceof com.xavier.backend.access.Actor).orElse(false);
    }

    public static void leave() {
        Deque<Boolean> marks = MARKS.get();
        marks.poll();
        if (marks.isEmpty()) {
            MARKS.remove();
        }
    }
}
