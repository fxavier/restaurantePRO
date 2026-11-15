package com.xavier.backend.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.access.SystemScoped;
import com.xavier.backend.shared.events.EventPublicationRecovery;
import com.xavier.backend.shared.tenancy.JobLeases;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantContext;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D185 (A90 mechanism): every actor-less context is inside one tenant. A reach-ALL {@code @SystemScoped} method runs
 * once per ACTIVE tenant, each in its own binding (its transactions carry that tenant), skips a suspended tenant and a
 * tenant whose lease another instance holds, survives one tenant's failure (counted, no tenant label), and starts at
 * a rotating tenant; reach NONE runs once, unbound. A listener runs in its event's tenant, an event without a tenant
 * fails before the listener's transaction (the production advisor around a listener, ahead of its transaction), and
 * a republished event (the D88 recovery) binds its own tenant. Strict: the JUnit thread has no tenant, as a scheduler
 * thread.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, ActorlessTenantScopeTest.Probes.class})
@StrictTenantScope
class ActorlessTenantScopeTest {

    static final UUID T1 = UUID.fromString("0190b300-0000-7000-8000-000000000001");
    static final UUID T2 = UUID.fromString("0190b300-0000-7000-8000-000000000002");
    static final UUID SUSPENDED = UUID.fromString("0190b300-0000-7000-8000-000000000003");
    static final String JOB = "TenantJobs.sweep";

    /** What a probe saw: the bound tenant, and the tenant its own transaction was bound to. */
    record Seen(String where, UUID bound, String transaction, String thread) {
    }

    static final List<Seen> SEEN = new CopyOnWriteArrayList<>();

    @Autowired
    TenantJobs jobs;
    @Autowired
    TenantScopes tenants;
    @Autowired
    ApplicationEventPublisher publisher;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MeterRegistry registry;
    @Autowired
    EventPublicationRecovery recovery;
    @Autowired
    StoreScopeAdvisor advisor;

    @BeforeEach
    void tenants() {
        TenantTestSupport.ensureTenant(T1, "actorless-1", "ACTIVE");
        TenantTestSupport.ensureTenant(T2, "actorless-2", "ACTIVE");
        TenantTestSupport.ensureTenant(SUSPENDED, "actorless-3", "SUSPENDED");
        SEEN.clear();
        TenantJobs.failing = null;
        Listeners.flakyFailures.set(0);
    }

    /** Back to no ACTIVE test tenant: the other suites' jobs iterate the bootstrap tenant only. */
    @AfterEach
    void close() {
        TenantTestSupport.ensureTenant(T1, "actorless-1", "CLOSED");
        TenantTestSupport.ensureTenant(T2, "actorless-2", "CLOSED");
    }

    @Test
    void aReachAllJobRunsOncePerActiveTenantEachInItsOwnBinding() {
        jobs.sweep();

        assertThat(SEEN).extracting(Seen::bound)
                .containsExactlyInAnyOrder(TenantTestSupport.BOOTSTRAP, T1, T2)
                .doesNotContain(SUSPENDED);
        assertThat(SEEN).allSatisfy(seen -> assertThat(seen.transaction()).isEqualTo(seen.bound().toString()));
    }

    @Test
    void oneTenantsFailureIsCountedAndTheOtherTenantsStillRun() {
        TenantJobs.failing = T1;
        double before = failures();

        jobs.sweep();

        assertThat(SEEN).extracting(Seen::bound).containsExactlyInAnyOrder(TenantTestSupport.BOOTSTRAP, T1, T2);
        assertThat(failures() - before).isEqualTo(1.0);
        assertThat(registry.find(StoreScopeAdvisor.TENANT_FAILURES).counters())
                .allSatisfy(counter -> assertThat(counter.getId().getTags()).extracting(tag -> tag.getKey())
                        .containsExactly("job"));
    }

    @Test
    void aTenantWhoseLeaseAnotherInstanceHoldsIsSkipped() {
        JobLeases other = new JobLeases(jdbc, transactions, Duration.ofMinutes(2), "another-instance");
        assertThat(other.tryAcquire(JOB, T2)).isTrue();
        try {
            jobs.sweep();
        } finally {
            other.release(JOB, T2);
        }

        assertThat(SEEN).extracting(Seen::bound).containsExactlyInAnyOrder(TenantTestSupport.BOOTSTRAP, T1);
    }

    @Test
    void eachTickStartsAtTheNextTenant() {
        jobs.sweep();
        UUID first = SEEN.getFirst().bound();
        SEEN.clear();

        jobs.sweep();

        assertThat(SEEN.getFirst().bound()).isNotEqualTo(first);
        assertThat(SEEN).hasSize(3);
    }

    @Test
    void aReachNoneJobRunsOnceWithNoTenant() {
        jobs.housekeep();

        assertThat(SEEN).singleElement().satisfies(seen -> {
            assertThat(seen.bound()).isNull();
            assertThat(seen.transaction()).isEmpty();
        });
    }

    @Test
    void aListenerRunsInItsEventsTenantOnTheListenerThread() {
        UUID marker = UUID.randomUUID();

        publishIn(T1, new Pinged(T1, UUID.randomUUID(), marker));

        Seen seen = await("ping:" + marker);
        assertThat(seen.bound()).isEqualTo(T1);
        assertThat(seen.transaction()).isEqualTo(T1.toString());
        assertThat(seen.thread()).isNotEqualTo(Thread.currentThread().getName());
    }

    @Test
    void anEventWithoutATenantFailsBeforeTheListenersTransaction() {
        UUID marker = UUID.randomUUID();
        AtomicInteger begun = new AtomicInteger();
        PlatformTransactionManager counting = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                begun.incrementAndGet();
                return transactions.getTransaction(definition);
            }

            @Override
            public void commit(TransactionStatus status) {
                transactions.commit(status);
            }

            @Override
            public void rollback(TransactionStatus status) {
                transactions.rollback(status);
            }
        };
        ProxyFactory factory = new ProxyFactory(new Listeners(tenants, transactions, jdbc));
        factory.setProxyTargetClass(true);
        factory.addAdvisor(advisor);
        factory.addAdvice(new TransactionInterceptor(counting, new AnnotationTransactionAttributeSource()));
        Listeners proxied = (Listeners) factory.getProxy();

        assertThatThrownBy(() -> proxied.on(new Untenanted(UUID.randomUUID(), marker)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("carries no tenantId");

        assertThat(begun).as("no transaction began").hasValue(0);
        assertThat(SEEN).noneSatisfy(seen -> assertThat(seen.where()).isEqualTo("untenanted:" + marker));
    }

    @Test
    void aRepublishedEventBindsItsOwnTenant() {
        UUID marker = UUID.randomUUID();
        publishIn(T2, new Flaky(T2, UUID.randomUUID(), marker));
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> status(marker), "FAILED"::equals);
        UUID publication = OwnerDatabase.shared().jdbc().sql("SELECT id FROM event_publication "
                + "WHERE serialized_event LIKE ?").param("%" + marker + "%").query(UUID.class).single();

        assertThat(recovery.resubmit(List.of(publication))).isEqualTo(1);

        Seen seen = await("flaky:" + marker);
        assertThat(seen.bound()).isEqualTo(T2);
        assertThat(seen.transaction()).isEqualTo(T2.toString());
    }

    private void publishIn(UUID tenant, Object event) {
        TenantTestSupport.inTenant(tenants, tenant, () -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> publisher.publishEvent(event)));
    }

    private static Seen await(String where) {
        return Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> SEEN.stream()
                .filter(seen -> seen.where().equals(where)).findFirst().orElse(null), found -> found != null);
    }

    private static String status(UUID marker) {
        List<String> statuses = OwnerDatabase.shared().jdbc().sql("SELECT status FROM event_publication "
                + "WHERE serialized_event LIKE ?").param("%" + marker + "%").query(String.class).list();
        return statuses.isEmpty() ? null : statuses.getFirst();
    }

    private double failures() {
        return registry.find(StoreScopeAdvisor.TENANT_FAILURES).tag("job", JOB).counters().stream()
                .mapToDouble(counter -> counter.count()).sum();
    }

    /** What a probe records: the thread's bound tenant, and the tenant of a transaction it begins. */
    static void record(String where, TenantScopes tenants, PlatformTransactionManager transactions, JdbcClient jdbc) {
        UUID bound = tenants.current().map(TenantContext::tenantId).orElse(null);
        String transaction = new TransactionTemplate(transactions).execute(status -> jdbc
                .sql("SELECT coalesce(current_setting('app.tenant_id', true), '')").query(String.class).single());
        SEEN.add(new Seen(where, bound, transaction, Thread.currentThread().getName()));
    }

    record Pinged(UUID tenantId, UUID storeId, UUID marker) {
    }

    record Untenanted(UUID storeId, UUID marker) {
    }

    record Flaky(UUID tenantId, UUID storeId, UUID marker) {
    }

    /** Test jobs: one of reach ALL (through a production scope), one of reach NONE. */
    static class TenantJobs {

        static volatile UUID failing;

        private final TenantScopes tenants;
        private final PlatformTransactionManager transactions;
        private final JdbcClient jdbc;

        TenantJobs(TenantScopes tenants, PlatformTransactionManager transactions, JdbcClient jdbc) {
            this.tenants = tenants;
            this.transactions = transactions;
            this.jdbc = jdbc;
        }

        @SystemScoped(SystemScope.ALERTS)
        public void sweep() {
            record("sweep", tenants, transactions, jdbc);
            if (tenants.requireTenant().equals(failing)) {
                throw new IllegalStateException("this tenant's sweep fails");
            }
        }

        @SystemScoped(SystemScope.HOUSEKEEPING)
        public void housekeep() {
            record("housekeep", tenants, transactions, jdbc);
        }
    }

    /** Test listeners: they record their binding; {@link Flaky} fails its first delivery. */
    static class Listeners {

        static final AtomicInteger flakyFailures = new AtomicInteger();

        private final TenantScopes tenants;
        private final PlatformTransactionManager transactions;
        private final JdbcClient jdbc;

        Listeners(TenantScopes tenants, PlatformTransactionManager transactions, JdbcClient jdbc) {
            this.tenants = tenants;
            this.transactions = transactions;
            this.jdbc = jdbc;
        }

        @ApplicationModuleListener
        void on(Pinged event) {
            record("ping:" + event.marker(), tenants, transactions, jdbc);
        }

        @ApplicationModuleListener
        void on(Untenanted event) {
            record("untenanted:" + event.marker(), tenants, transactions, jdbc);
        }

        @ApplicationModuleListener
        void on(Flaky event) {
            if (flakyFailures.getAndIncrement() == 0) {
                throw new IllegalStateException("first delivery fails");
            }
            record("flaky:" + event.marker(), tenants, transactions, jdbc);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probes {

        @Bean
        TenantJobs tenantJobs(TenantScopes tenants, PlatformTransactionManager transactions, JdbcClient jdbc) {
            return new TenantJobs(tenants, transactions, jdbc);
        }

        @Bean
        Listeners tenantListeners(TenantScopes tenants, PlatformTransactionManager transactions, JdbcClient jdbc) {
            return new Listeners(tenants, transactions, jdbc);
        }
    }
}
