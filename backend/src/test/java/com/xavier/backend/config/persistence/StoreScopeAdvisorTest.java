package com.xavier.backend.config.persistence;

import com.xavier.backend.shared.tenancy.ActiveTenants;
import com.xavier.backend.shared.tenancy.JobLeases;
import com.xavier.backend.shared.tenancy.TenantScopes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.access.StoreScope;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.modulith.events.ApplicationModuleListener;

/** D166: what the advisor applies, before anything else of the method (its transaction included). */
class StoreScopeAdvisorTest {

    static final UUID STORE = UUID.fromString("0190a000-0000-7000-8000-0000000000aa");

    record StoreEvent(UUID orderId, UUID storeId) {
    }

    record StorelessEvent(String name) {
    }

    static class Entry {

        StoreScopes scopes;

        @SystemScoped(SystemScope.RESERVATIONS)
        public StoreScope job() {
            return scopes.current();
        }

        @ApplicationModuleListener
        public StoreScope on(StoreEvent event) {
            return scopes.current();
        }

        @ApplicationModuleListener
        public StoreScope on(StorelessEvent event) {
            return scopes.current();
        }

        @SystemScoped(SystemScope.BOOTSTRAP)
        @ApplicationModuleListener
        public StoreScope onBootstrap(StorelessEvent event) {
            return scopes.current();
        }

        @SystemScoped(SystemScope.HOUSEKEEPING)
        public void refused() {
            throw new DataAccessResourceFailureException("x", new SQLException("store scope not set", "RS001"));
        }

        public StoreScope plain() {
            return scopes.current();
        }
    }

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final StoreScopes scopes = new StoreScopes(() -> StoreScope.UNSET,
            new StoreScopeViolations(registry, Clock.systemUTC()));
    final Entry entry = proxied();

    @Test
    void aSystemScopedMethodRunsInItsSystemScope() {
        StoreScope inside = entry.job();

        assertThat(inside.origin()).isEqualTo("system:RESERVATIONS");
        assertThat(inside.allStoresSetting()).isEqualTo("on");
        assertThat(scopes.current()).as("restored after").isEqualTo(StoreScope.UNSET);
    }

    @Test
    void aListenerRunsInItsEventsStore() {
        StoreScope inside = entry.on(new StoreEvent(UUID.randomUUID(), STORE));

        assertThat(inside.storeIdsLiteral()).isEqualTo("{" + STORE + "}");
        assertThat(inside.allStoresSetting()).isEqualTo("off");
        assertThat(inside.origin()).isEqualTo("listener:Entry.on");
    }

    @Test
    void aListenerOfAnEventWithoutAStoreFailsBeforeItRuns() {
        assertThatThrownBy(() -> entry.on(new StorelessEvent("x"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("listener:Entry.on");
        assertThatThrownBy(() -> entry.on(new StoreEvent(UUID.randomUUID(), null)))
                .as("a null storeId too").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSystemScopeOutranksTheListenerRule() {
        assertThat(entry.onBootstrap(new StorelessEvent("x")).origin()).isEqualTo("system:BOOTSTRAP");
    }

    @Test
    void anUnannotatedMethodIsLeftAlone() {
        assertThat(entry.plain()).isEqualTo(StoreScope.UNSET);
    }

    @Test
    void aRefusalByRowLevelSecurityIsCountedWithItsOrigin() {
        assertThatThrownBy(entry::refused).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(registry.get("store_scope_violations").tag("kind", "scope_missing").counter().count())
                .isEqualTo(1.0);
    }

    /** A reach-ALL job: records the tenant each run is bound to. */
    static class TenantJob {

        TenantScopes tenants;
        final List<UUID> ran = new ArrayList<>();

        /** When set, the first run lasts until the monotonic clock passes it (a slow tenant), no sleep. */
        volatile long firstRunUntilNanos;

        @SystemScoped(SystemScope.ALERTS)
        public void run() {
            ran.add(tenants.requireTenant());
            if (ran.size() == 1 && firstRunUntilNanos != 0) {
                while (System.nanoTime() - firstRunUntilNanos <= 0) {
                    Thread.onSpinWait();
                }
            }
        }

        public void slowFirstRunUntil(long nanos) {
            firstRunUntilNanos = nanos;
        }

        /** Through the proxy: a field read on it would be the proxy's own. */
        public List<UUID> ran() {
            return ran;
        }
    }

    /** Leases that grant every tenant asked for, until {@code deadlineNanos}, and record what is released. */
    static final class GrantingLeases extends JobLeases {

        final long startByNanos;
        final List<UUID> released = new ArrayList<>();

        GrantingLeases(long startByNanos) {
            super(null, null, Duration.ofMinutes(2), "unit");
            this.startByNanos = startByNanos;
        }

        @Override
        public Batch acquire(String job, Supplier<List<UUID>> tenants) {
            return new Batch(tenants.get(), startByNanos);
        }

        @Override
        public void release(String job, Collection<UUID> tenants) {
            released.addAll(tenants);
        }
    }

    static final List<UUID> TENANTS = List.of(UUID.fromString("0190a000-0000-7000-8000-000000000001"),
            UUID.fromString("0190a000-0000-7000-8000-000000000002"));

    /** D185 with S77's batched leases: every leased tenant runs in its own binding, and all are released after. */
    @Test
    void whileTheTicksLeasesAreLiveEachTenantRunsAndAllAreReleasedAfter() {
        GrantingLeases leases = new GrantingLeases(System.nanoTime() + Duration.ofMinutes(2).toNanos());

        TenantJob job = tenantJob(leases);
        job.run();

        assertThat(job.ran()).containsExactlyInAnyOrderElementsOf(TENANTS);
        assertThat(leases.released).containsExactlyInAnyOrderElementsOf(TENANTS);
    }

    /** D185: no tenant starts once the tick's leases may have passed to another instance; they are released. */
    @Test
    void noTenantStartsOnceTheTicksLeasesHaveRunOut() {
        GrantingLeases leases = new GrantingLeases(System.nanoTime() - 1);

        TenantJob job = tenantJob(leases);
        job.run();

        assertThat(job.ran()).isEmpty();
        assertThat(leases.released).containsExactlyInAnyOrderElementsOf(TENANTS);
    }

    /** G5: a tenant reached after the batch's start-by (ttl/2) is not started, and its lease is released. */
    @Test
    void aTenantReachedAfterHalfTheTtlIsNotStartedAndItsLeaseIsReleased() {
        long startBy = System.nanoTime() + Duration.ofMillis(50).toNanos();
        GrantingLeases leases = new GrantingLeases(startBy);
        TenantJob job = tenantJob(leases);
        job.slowFirstRunUntil(startBy);

        job.run();

        assertThat(job.ran()).as("only the first tenant started").hasSize(1);
        assertThat(leases.released).containsExactlyInAnyOrderElementsOf(TENANTS);
    }

    private TenantJob tenantJob(JobLeases leases) {
        TenantScopes tenants = new TenantScopes(scopes, java.util.Optional::empty);
        ActiveTenants directory = new ActiveTenants(null, null) {
            @Override
            public List<UUID> ids() {
                return TENANTS;
            }
        };
        TenantJob target = new TenantJob();
        target.tenants = tenants;
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new StoreScopeAdvisor(provider(scopes), provider(tenants), provider(directory),
                provider(leases), provider(null), provider(null)));
        return (TenantJob) factory.getProxy();
    }

    @Test
    void itRunsBeforeTheTransactionInterceptor() {
        assertThat(new StoreScopeAdvisor(provider(scopes), provider(null), provider(null), provider(null),
                provider(null), provider(null)).getOrder())
                .isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
    }

    private Entry proxied() {
        Entry target = new Entry();
        target.scopes = scopes;
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new StoreScopeAdvisor(provider(scopes), provider(null), provider(null), provider(null),
                provider(new StoreScopeViolations(registry, Clock.systemUTC())), provider(null)));
        return (Entry) factory.getProxy();
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T bean) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (bean != null) {
            factory.registerSingleton("bean", bean);
        }
        @SuppressWarnings("unchecked")
        Class<T> type = bean == null ? (Class<T>) Object.class : (Class<T>) bean.getClass();
        return factory.getBeanProvider(type);
    }
}
