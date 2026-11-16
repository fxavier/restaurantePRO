package com.xavier.backend.shared.tenancy;

import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.SystemScope;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The tenant of the current thread (D177, D178, D185) and the ways to bind one explicitly. Resolution order: the
 * innermost explicit binding (a system scope, a listener's event, a propagated task, or an explicit "no tenant"),
 * else the tenant of the authenticated principal ({@link TenantContext} as its details: {@code config.security}'s
 * resolution, {@code @WithActor} in tests), else the request's pre-resolution tenant ({@link BootstrapTenantRequests},
 * until S72 resolves it from the membership), else the {@link TenantFallback}. {@code TenantBindingTransactionManager}
 * writes {@link #current()} into {@code app.tenant_id} at every transaction begin.
 *
 * <p>A tenant cannot change inside a transaction: the code inside would join the outer transaction and keep its
 * binding (D178). So {@link #run} and {@link #call} refuse to start while one is active, as {@link StoreScopes} does.
 * A system scope binds both the tenant and its store scope ({@link SystemScope#scope()}, reach {@code ALL} = every
 * store of that tenant).
 */
@Component
public class TenantScopes {

    /** An explicit binding; {@code context == null} binds no tenant on purpose (a reach-NONE system scope). */
    private record Frame(TenantContext context) {
    }

    private static final ThreadStack<Frame> EXPLICIT = new ThreadStack<>();
    private static final ThreadLocal<TenantContext> REQUEST = new ThreadLocal<>();
    private static final TenantContext PRE_RESOLUTION = new TenantContext(BootstrapTenant.ID, MembershipKind.SYSTEM,
            null, "request:pre-resolution");
    private static final String TASK = "task:";

    private final ObjectProvider<StoreScopes> stores;
    private final TenantFallback fallback;

    /**
     * @param stores the store scopes, lazily: a module test that boots {@code shared} without {@code access} has
     *               none, and then a binding carries the tenant only
     */
    @Autowired
    public TenantScopes(ObjectProvider<StoreScopes> stores, TenantFallback fallback) {
        this.stores = stores;
        this.fallback = fallback;
    }

    /** With the store scopes at hand (unit tests). */
    public TenantScopes(StoreScopes stores, TenantFallback fallback) {
        this(new FixedStoreScopes(stores), fallback);
    }

    /** The tenant a transaction begun now is bound to; empty = unbound ({@code ''}, a protected table raises RS003). */
    public Optional<TenantContext> current() {
        Frame explicit = EXPLICIT.peek();
        if (explicit != null) {
            return Optional.ofNullable(explicit.context());
        }
        Optional<TenantContext> principal = CurrentAuthentication.read()
                .map(authentication -> authentication.getDetails() instanceof TenantContext context ? context : null);
        if (principal.isPresent()) {
            return principal;
        }
        TenantContext request = REQUEST.get();
        if (request != null) {
            return Optional.of(request);
        }
        return fallback.whenUnbound();
    }

    /**
     * The bound tenant's id.
     *
     * @throws IllegalStateException when no tenant is bound (a code path that forgot its binding)
     */
    public UUID requireTenant() {
        return current().map(TenantContext::tenantId).orElseThrow(() -> new IllegalStateException(
                "No tenant is bound to this thread (D178): bind one with TenantScopes.run or a tenant-bound actor"));
    }

    /** Runs {@code action} in {@code tenantId}, in a named system scope. */
    public void run(UUID tenantId, SystemScope scope, Runnable action) {
        call(tenantId, scope, () -> {
            action.run();
            return null;
        });
    }

    /** Calls {@code action} in {@code tenantId}, in a named system scope. */
    public <T> T call(UUID tenantId, SystemScope scope, Supplier<T> action) {
        TenantContext context = new TenantContext(tenantId, MembershipKind.SYSTEM, null, "system:" + scope.name());
        return within(new Frame(context), context.origin(), () -> inStoreScope(scope, action));
    }

    /**
     * A listener of an event of {@code tenantId} (D185): the store scope is {@code scope}'s when the listener is
     * {@code @SystemScoped}, otherwise the event's store.
     *
     * @throws IllegalStateException when {@code tenantId} is {@code null}, before any transaction
     */
    public <T> T callForEvent(UUID tenantId, SystemScope scope, UUID storeId, String origin, Supplier<T> action) {
        if (tenantId == null) {
            throw new IllegalStateException("No tenant to bind " + origin + " to: its event carries no tenantId "
                    + "(D185)");
        }
        TenantContext context = new TenantContext(tenantId, MembershipKind.SYSTEM, null, origin);
        StoreScopes storeScopes = stores.getIfAvailable();
        return within(new Frame(context), origin, () -> storeScopes == null ? action.get()
                : scope != null ? storeScopes.call(scope, action) : storeScopes.callForStore(storeId, origin, action));
    }

    /**
     * Calls {@code action} in a resolved tenant context, keeping the thread's store scope: the binding of a request's
     * resolved membership ({@code config.security}, S72) or of a test. Only resolution and test sources create a
     * {@link TenantContext} (ArchUnit {@code onlyResolutionCreatesTenantContext}).
     */
    public <T> T callIn(TenantContext context, Supplier<T> action) {
        return within(new Frame(Objects.requireNonNull(context, "context")), context.origin(), action);
    }

    /** A reach-NONE system scope: no tenant at all (PLATFORM tables only), whatever the thread had. */
    public <T> T callUnbound(SystemScope scope, Supplier<T> action) {
        return within(new Frame(null), "system:" + scope.name(), () -> inStoreScope(scope, action));
    }

    /**
     * Wraps an executor task so it runs, on its worker thread, in the tenant and the store scope resolved now (at
     * submission). The same as {@link StoreScopes#propagate}, which carries the tenant too.
     */
    public Runnable propagate(Runnable task) {
        StoreScopes storeScopes = stores.getIfAvailable();
        return storeScopes == null ? bindCurrentTo(task) : storeScopes.propagate(task);
    }

    /**
     * The tenant half of {@link StoreScopes#propagate}: the tenant resolved now, bound around {@code task} on the
     * thread that runs it (an explicit "no tenant" when none is bound now).
     */
    public Runnable bindCurrentTo(Runnable task) {
        Optional<TenantContext> captured = current();
        Frame frame = new Frame(captured.map(context -> context.origin().startsWith(TASK) ? context
                : new TenantContext(context.tenantId(), context.kind(), context.principalId(),
                        TASK + context.origin())).orElse(null));
        return () -> EXPLICIT.within(frame, () -> {
            task.run();
            return null;
        });
    }

    /** {@link BootstrapTenantRequests}: the request thread runs in the pre-resolution tenant until it ends. */
    static void enterRequest() {
        REQUEST.set(PRE_RESOLUTION);
    }

    static void leaveRequest() {
        REQUEST.remove();
    }

    private <T> T inStoreScope(SystemScope scope, Supplier<T> action) {
        StoreScopes storeScopes = stores.getIfAvailable();
        return storeScopes == null ? action.get() : storeScopes.call(scope, action);
    }

    /** A provider of one given instance. */
    private record FixedStoreScopes(StoreScopes scopes) implements ObjectProvider<StoreScopes> {

        @Override
        public StoreScopes getObject() {
            return scopes;
        }

        @Override
        public StoreScopes getObject(Object... args) {
            return scopes;
        }

        @Override
        public StoreScopes getIfAvailable() {
            return scopes;
        }

        @Override
        public StoreScopes getIfUnique() {
            return scopes;
        }
    }

    private static <T> T within(Frame frame, String origin, Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The tenant cannot change inside a transaction (" + origin + "): the code "
                    + "would keep the transaction's connection and its binding (D178)");
        }
        return EXPLICIT.within(frame, action);
    }
}
