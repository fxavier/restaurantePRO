package com.xavier.backend.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StoreScope;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * D177/D178/D185: what tenant a thread works in. Resolution order: an explicit binding, else the authenticated
 * principal's resolved tenant, else the fallback; a system scope binds the
 * tenant and its store scope together; a tenant never changes inside a transaction; a task carries its submitter's
 * tenant and store scope; an event without a tenant is refused. No database: the bindings themselves.
 */
@StrictTenantScope
class TenantScopesTest {

    static final UUID A = TenantTestSupport.A;
    static final UUID B = TenantTestSupport.B;
    static final UUID STORE = UUID.fromString("0190b200-0000-7000-8000-000000000001");

    final TenantContext fallbackContext = new TenantContext(BootstrapTenant.ID, MembershipKind.SYSTEM, null,
            "fallback");
    Optional<TenantContext> fallback = Optional.empty();

    /** The context's wiring: store scopes reach the tenant scopes lazily, for task propagation. */
    final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    final StoreScopes stores = new StoreScopes(() -> StoreScope.UNSET,
            new StoreScopeViolations(new SimpleMeterRegistry(), Clock.systemUTC()),
            beans.getBeanProvider(TenantScopes.class));
    final TenantScopes tenants = registered(new TenantScopes(stores, () -> fallback));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anUnboundThreadHasNoTenantAndRequireTenantRefusesIt() {
        assertThat(tenants.current()).isEmpty();
        assertThatThrownBy(tenants::requireTenant).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant is bound");
    }

    @Test
    void theResolutionOrderIsExplicitThenPrincipalThenFallback() {
        fallback = Optional.of(fallbackContext);
        assertThat(tenants.requireTenant()).as("fallback").isEqualTo(BootstrapTenant.ID);

        authenticateIn(A);
        assertThat(tenants.requireTenant()).as("the principal's resolved tenant").isEqualTo(A);

        UUID explicit = tenants.call(B, SystemScope.ALERTS, tenants::requireTenant);
        assertThat(explicit).as("an explicit binding outranks the principal").isEqualTo(B);
        assertThat(tenants.requireTenant()).as("and ends with its call").isEqualTo(A);
    }

    @Test
    void aSystemScopeBindsTheTenantAndItsStoreScope() {
        List<Object> seen = tenants.call(A, SystemScope.STOCK_RECONCILE,
                () -> List.of(tenants.requireTenant(), stores.current(), tenants.current().orElseThrow().origin()));

        assertThat(seen).containsExactly(A, SystemScope.STOCK_RECONCILE.scope(), "system:STOCK_RECONCILE");
        assertThat(tenants.current()).isEmpty();
    }

    @Test
    void aReachNoneScopeBindsNoTenantEvenOnABoundThread() {
        authenticateIn(A);

        Optional<TenantContext> inside = tenants.callUnbound(SystemScope.EVENT_MAINTENANCE, tenants::current);

        assertThat(inside).isEmpty();
        assertThat(tenants.requireTenant()).isEqualTo(A);
    }

    @Test
    void aListenerRunsInItsEventsTenantAndStoreAndAnEventWithoutATenantIsRefused() {
        List<Object> seen = tenants.callForEvent(A, null, STORE, "listener:Probe.on",
                () -> List.of(tenants.requireTenant(), stores.current()));

        assertThat(seen).containsExactly(A, StoreScope.ofStore(STORE, "listener:Probe.on"));
        assertThat(tenants.callForEvent(B, SystemScope.BOOTSTRAP, null, "listener:Boot.on", tenants::requireTenant))
                .isEqualTo(B);
        assertThatThrownBy(() -> tenants.callForEvent(null, null, STORE, "listener:Probe.on", () -> "ran"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("carries no tenantId");
    }

    @Test
    void theTenantCannotChangeInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> tenants.run(A, SystemScope.ALERTS, () -> { }))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("inside a transaction");
            assertThatThrownBy(() -> TenantTestSupport.inTenant(tenants, A, () -> { }))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void aTaskRunsInItsSubmittersTenantAndStoreScopeOnItsWorker() throws Exception {
        Runnable[] task = new Runnable[1];
        Object[] seen = new Object[3];
        tenants.run(A, SystemScope.PRINT_WORKER, () -> task[0] = tenants.propagate(() -> {
            seen[0] = tenants.requireTenant();
            seen[1] = stores.current().allStores();
            seen[2] = tenants.current().orElseThrow().origin();
        }));

        CompletableFuture.runAsync(task[0]).get(10, TimeUnit.SECONDS);

        assertThat(seen).containsExactly(A, true, "task:system:PRINT_WORKER");
    }

    @Test
    void aTaskSubmittedWithNoTenantRunsWithNoneWhateverItsWorkerHad() {
        Runnable task = tenants.propagate(() -> assertThat(tenants.current()).isEmpty());

        tenants.run(B, SystemScope.ALERTS, task);
    }

    private void authenticateIn(UUID tenant) {
        Actor actor = Actor.account(UUID.randomUUID(), Set.of(Permission.ORDER_VIEW.authority(STORE)));
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(actor,
                null, List.of());
        authentication.setDetails(new TenantContext(tenant, MembershipKind.ACCOUNT, actor.userId(), "test"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private TenantScopes registered(TenantScopes created) {
        beans.registerSingleton("tenants", created);
        return created;
    }
}
