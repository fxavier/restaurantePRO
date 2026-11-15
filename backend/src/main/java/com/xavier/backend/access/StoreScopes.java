package com.xavier.backend.access;

import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.tenancy.CurrentAuthentication;
import com.xavier.backend.shared.tenancy.TenantScopes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The store scope of the current thread (D164) and the ways to set one explicitly (D166). Resolution order: the
 * innermost explicit system, listener or task scope, else the authenticated {@link Actor}, else the
 * {@link StoreScopeFallback}. {@code config.persistence.TenantBindingTransactionManager} writes {@link #current()} into
 * the transaction's connection at every begin, with the tenant of {@link TenantScopes} (D178): the store scope is
 * always within the bound tenant.
 *
 * <p>A scope cannot change inside a transaction: the code inside would join the outer transaction and its connection,
 * silently keeping the outer scope (D165). So {@link #run}, {@link #call} and {@link #callForStore} refuse to start
 * while one is active, as {@code ExternalCalls.requireNoTransaction()} does (D48).
 */
@Component
public class StoreScopes {

    private static final ThreadLocal<Deque<StoreScope>> EXPLICIT = ThreadLocal.withInitial(ArrayDeque::new);
    private static final String TASK = "task:";

    private final StoreScopeFallback fallback;
    private final StoreScopeViolations violations;
    private final ObjectProvider<TenantScopes> tenants;

    /** Without tenants: for unit tests of the store scope alone ({@link #propagate} then carries no tenant). */
    public StoreScopes(StoreScopeFallback fallback, StoreScopeViolations violations) {
        this(fallback, violations, null);
    }

    @Autowired
    public StoreScopes(StoreScopeFallback fallback, StoreScopeViolations violations,
                       ObjectProvider<TenantScopes> tenants) {
        this.fallback = fallback;
        this.violations = violations;
        this.tenants = tenants;
    }

    /**
     * The scope a transaction begun now gets. Spring Security loads a request's context lazily, from the session in
     * the database; the transaction of that read takes the fallback (the session tables are unscoped), so resolving
     * the actor never recurses ({@link CurrentAuthentication}).
     */
    public StoreScope current() {
        StoreScope explicit = EXPLICIT.get().peek();
        if (explicit != null) {
            return explicit;
        }
        return CurrentAuthentication.read()
                .map(authentication -> authentication.getPrincipal() instanceof Actor actor ? StoreScope.of(actor)
                        : null)
                .orElseGet(fallback::whenNoActor);
    }

    /** Runs {@code action} in a named system scope. */
    public void run(SystemScope scope, Runnable action) {
        within(scope.scope(), () -> {
            action.run();
            return null;
        });
    }

    /** Calls {@code action} in a named system scope. */
    public <T> T call(SystemScope scope, Supplier<T> action) {
        return within(scope.scope(), action);
    }

    /**
     * Calls {@code action} in the scope of one store: a listener of an event of {@code storeId} (D166).
     *
     * @throws IllegalStateException when {@code storeId} is {@code null} (an event of no store needs a system scope)
     */
    public <T> T callForStore(UUID storeId, String origin, Supplier<T> action) {
        if (storeId == null) {
            throw new IllegalStateException("No store to scope " + origin + " to: its event carries no storeId; "
                    + "an event of no store needs @SystemScoped (D166)");
        }
        return within(StoreScope.ofStore(storeId, origin), action);
    }

    /**
     * Wraps an executor task so it runs, on its worker thread, in the scope resolved now (at submission), with origin
     * {@code task:<origin>}, and in the tenant resolved now ({@link TenantScopes#bindCurrentTo}, D185). A refusal by
     * row-level security escaping the task is counted (D170) and rethrown.
     */
    public Runnable propagate(Runnable task) {
        TenantScopes tenantScopes = tenants == null ? null : tenants.getIfAvailable();
        Runnable storeScoped = propagateStoreScope(task);
        return tenantScopes == null ? storeScoped : tenantScopes.bindCurrentTo(storeScoped);
    }

    private Runnable propagateStoreScope(Runnable task) {
        StoreScope captured = current();
        String origin = captured.origin().startsWith(TASK) ? captured.origin() : TASK + captured.origin();
        StoreScope taskScope = captured.withOrigin(origin);
        return () -> {
            Deque<StoreScope> stack = EXPLICIT.get();
            stack.push(taskScope);
            try {
                task.run();
            } catch (RuntimeException e) {
                StoreScopeViolations.classify(e).ifPresent(kind -> violations.record(kind, origin));
                throw e;
            } finally {
                pop(stack);
            }
        };
    }

    private static <T> T within(StoreScope scope, Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The store scope cannot change inside a transaction (" + scope.origin()
                    + "): the code would keep the transaction's connection and its scope (D165)");
        }
        Deque<StoreScope> stack = EXPLICIT.get();
        stack.push(scope);
        try {
            return action.get();
        } finally {
            pop(stack);
        }
    }

    private static void pop(Deque<StoreScope> stack) {
        stack.pop();
        if (stack.isEmpty()) {
            EXPLICIT.remove();
        }
    }
}
