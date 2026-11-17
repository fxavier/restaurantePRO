package com.xavier.backend.config.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.SystemScope;
import com.xavier.backend.shared.persistence.StoreScopeViolations;
import com.xavier.backend.shared.tenancy.TenantScopes;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D178 (A83, transaction part; A73 carried over): every transaction is bound at begin with {@code SET LOCAL} semantics
 * on its own connection, and nothing survives it. As the application role, on the real pool (two connections, so a
 * {@code REQUIRES_NEW} can run and every physical connection can be inspected after a commit; without the test-only
 * fixture checkouts, so the pool is production's), on a TENANT_STORE scratch table and on {@code organization.store}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=2",
        "restaurantpro.test.fixture-checkouts=false"})
class TenantBindingTransactionManagerTest {

    static final UUID STORE_A = UUID.fromString("0190b100-0000-7000-8000-0000000000aa");
    static final UUID STORE_B = UUID.fromString("0190b100-0000-7000-8000-0000000000bb");

    static RlsScratch scratch;

    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    DataSource dataSource;
    @Autowired
    TenantScopes tenants;
    @Autowired
    ObjectProvider<TenantScopes> tenantProvider;
    @Autowired
    ObjectProvider<StoreScopes> storeProvider;
    @Autowired
    EntityManagerFactory entityManagerFactory;
    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    @BeforeAll
    static void scratchTable() {
        TenantTestSupport.ensureTenants();
        scratch = RlsScratch.createTenantStore("binding");
        scratch.insert(TenantTestSupport.A, STORE_A, "a-store-a");
        scratch.insert(TenantTestSupport.A, STORE_B, "a-store-b");
        scratch.insert(TenantTestSupport.B, STORE_A, "b-store-a");
    }

    @AfterAll
    static void dropScratch() {
        scratch.close();
    }

    @Test
    void theOnlyTransactionManagerBindsTheTenantAndTheStoreScopeInsideTheTransaction() {
        Map<String, Object> inside = TenantTestSupport.inTenant(tenants, TenantTestSupport.A,
                () -> new TransactionTemplate(transactions).execute(status -> settings()));

        assertThat(transactions).isInstanceOf(TenantBindingTransactionManager.class);
        assertThat(inside).containsEntry("tenant", TenantTestSupport.A.toString())
                .containsEntry("all_stores", "on").containsEntry("origin", TenantTestSupport.ORIGIN);
    }

    @Test
    void afterACommitAndAfterARollbackNoPhysicalConnectionCarriesATenant() throws SQLException {
        int committed = TenantTestSupport.inTenant(tenants, TenantTestSupport.A,
                () -> new TransactionTemplate(transactions).execute(status -> pid()));
        assertThat(everyPoolConnection()).as("after the commit of pid %s", committed)
                .allSatisfy((pid, tenant) -> assertThat(tenant).isNullOrEmpty())
                .containsKey(committed);

        int rolledBack = TenantTestSupport.inTenant(tenants, TenantTestSupport.B,
                () -> new TransactionTemplate(transactions).execute(status -> {
                    status.setRollbackOnly();
                    return pid();
                }));
        assertThat(everyPoolConnection()).as("after the rollback of pid %s", rolledBack)
                .allSatisfy((pid, tenant) -> assertThat(tenant).isNullOrEmpty())
                .containsKey(rolledBack);
    }

    @Test
    void alternatingTenantsOnOnePhysicalConnectionNeverSeeEachOthersRows() {
        Set<Integer> pids = new HashSet<>();
        List<String> seen = new ArrayList<>();
        for (UUID tenant : List.of(TenantTestSupport.A, TenantTestSupport.B, TenantTestSupport.A,
                TenantTestSupport.B)) {
            seen.add(TenantTestSupport.inTenant(tenants, tenant, () -> new TransactionTemplate(transactions)
                    .execute(status -> {
                        pids.add(pid());
                        return String.join(",", labels());
                    })));
        }

        assertThat(seen).containsExactly("a-store-a,a-store-b", "b-store-a", "a-store-a,a-store-b", "b-store-a");
        assertThat(pids).as("one physical connection throughout").hasSize(1);
    }

    @Test
    void aRequiresNewTransactionIsBoundFromTheThreadsContextOnItsOwnConnection() {
        TransactionTemplate inner = new TransactionTemplate(transactions);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        List<Object> seen = TenantTestSupport.inTenant(tenants, TenantTestSupport.A,
                () -> new TransactionTemplate(transactions).execute(outer -> {
                    int outerPid = pid();
                    Map<String, Object> nested = inner.execute(status -> {
                        Map<String, Object> settings = new java.util.HashMap<>(settings());
                        settings.put("pid", pid());
                        return settings;
                    });
                    return List.of(outerPid, nested.get("pid"), nested.get("tenant"), settings().get("tenant"));
                }));

        assertThat(seen.get(1)).as("its own connection").isNotEqualTo(seen.get(0));
        assertThat(seen.get(2)).as("REQUIRES_NEW rebinds").isEqualTo(TenantTestSupport.A.toString());
        assertThat(seen.get(3)).as("the outer binding is intact").isEqualTo(TenantTestSupport.A.toString());
    }

    @Test
    void aReadOnlyTransactionIsBoundTheSameWay() {
        TransactionTemplate readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);

        List<String> labels = TenantTestSupport.inTenant(tenants, TenantTestSupport.B,
                () -> readOnly.execute(status -> labels()));

        assertThat(labels).containsExactly("b-store-a");
    }

    @Test
    void anAutocommitStatementOnAProtectedTableRaisesRS003() {
        AppRoleSession.assertRefused(() -> jdbc.sql("SELECT count(*) FROM organization.store").query(Long.class)
                .single(), StoreScopeViolations.Kind.TENANT_MISSING);
    }

    @Test
    void aThreadWithNoTenantBindsEmptySoATenantTableFailsClosed() throws Exception {
        Map<String, Object> settings = CompletableFuture.supplyAsync(
                () -> new TransactionTemplate(transactions).execute(status -> settings())).get(30, TimeUnit.SECONDS);
        Throwable refused = CompletableFuture.supplyAsync(() -> {
            try {
                new TransactionTemplate(transactions).execute(status -> labels());
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        }).get(30, TimeUnit.SECONDS);

        assertThat(settings.get("tenant")).as("no context binds ''").isEqualTo("");
        assertThat(StoreScopeViolations.classify(refused)).contains(StoreScopeViolations.Kind.TENANT_MISSING);
    }

    @Test
    void anInsertTakesTheBoundTenantAndAnotherTenantsRowOrTenantChangeIsRefused() {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        UUID id = UUID.randomUUID();

        UUID stored = TenantTestSupport.inTenant(tenants, TenantTestSupport.A, () -> tx.execute(status -> {
            jdbc.sql("INSERT INTO " + scratch.table() + " (id, store_id, label) VALUES (?, ?, 'new')").param(id)
                    .param(STORE_A).update();
            return jdbc.sql("SELECT tenant_id FROM " + scratch.table() + " WHERE id = ?").param(id)
                    .query(UUID.class).single();
        }));
        assertThat(stored).as("DEFAULT shared.rls_tenant_id()").isEqualTo(TenantTestSupport.A);

        AppRoleSession.assertRefused(() -> TenantTestSupport.inTenant(tenants, TenantTestSupport.A,
                () -> tx.execute(status -> jdbc.sql("INSERT INTO " + scratch.table()
                        + " (id, tenant_id, store_id, label) VALUES (?, ?, ?, 'forged')").param(UUID.randomUUID())
                        .param(TenantTestSupport.B).param(STORE_A).update())),
                StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
        AppRoleSession.assertRefused(() -> TenantTestSupport.inTenant(tenants, TenantTestSupport.A,
                () -> tx.execute(status -> jdbc.sql("UPDATE " + scratch.table() + " SET tenant_id = ? WHERE id = ?")
                        .param(TenantTestSupport.B).param(id).update())),
                StoreScopeViolations.Kind.STORE_CHANGE);
    }

    @Test
    void aFailingBindingRollsBackReleasesItsConnectionAndThrows() {
        int[] failedPid = {0};
        TenantBindingTransactionManager failing = new TenantBindingTransactionManager(tenantProvider, storeProvider,
                StoreScopeEnforcement.ENFORCE) {
            @Override
            protected void bind(Connection connection) throws SQLException {
                try (ResultSet row = connection.createStatement().executeQuery("SELECT pg_backend_pid()")) {
                    row.next();
                    failedPid[0] = row.getInt(1); // the transaction has now run a statement
                }
                throw new SQLException("binding refused");
            }
        };
        failing.setEntityManagerFactory(entityManagerFactory);
        failing.afterPropertiesSet();
        boolean[] ran = {false};

        assertThatThrownBy(() -> new TransactionTemplate(failing).executeWithoutResult(status -> ran[0] = true))
                .isInstanceOf(CannotCreateTransactionException.class)
                .hasRootCauseMessage("binding refused");

        assertThat(ran[0]).as("no statement of the caller ran").isFalse();
        assertThat(TransactionSynchronizationManager.getResourceMap()).as("nothing left bound").isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        try (AppRoleSession sameRole = AppRoleSession.open()) { // a role sees its own sessions' state
            assertThat(sameRole.jdbc().sql("SELECT state FROM pg_stat_activity WHERE pid = ?").param(failedPid[0])
                    .query(String.class).single()).as("rolled back, not left in a transaction").isEqualTo("idle");
        }
        // a scheduled job of this context may hold the other connection for a moment; the failed one is returned
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections())
                .as("the connection went back to the pool").isZero());
        Object rebound = TenantTestSupport.inTenant(tenants, TenantTestSupport.A,
                () -> new TransactionTemplate(transactions).execute(status -> settings().get("tenant")));
        assertThat(rebound).as("the pool still binds").isEqualTo(TenantTestSupport.A.toString());
    }

    /**
     * D173 carried over to D178: a listener of tenant A and a request of tenant B, both transactions open at once (a
     * barrier, no sleeps), each on its own connection of one pool: each writes its own tenant's row and is refused the
     * other's ({@code 42501}).
     */
    @Test
    void twoTransactionsOpenAtOnceInTwoTenantsEachWriteOnlyTheirOwn() throws Exception {
        CyclicBarrier bothOpen = new CyclicBarrier(2);
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<List<Object>> inA = threads.submit(() -> writeOwnThenOther(TenantTestSupport.A,
                    TenantTestSupport.B, bothOpen));
            java.util.concurrent.Future<List<Object>> inB = threads.submit(() -> writeOwnThenOther(TenantTestSupport.B,
                    TenantTestSupport.A, bothOpen));

            for (List<Object> outcome : List.of(inA.get(60, TimeUnit.SECONDS), inB.get(60, TimeUnit.SECONDS))) {
                assertThat(outcome.get(0)).as("its own row").isEqualTo(1);
                assertThat(outcome.get(1)).as("the other tenant's row").isEqualTo(
                        StoreScopeViolations.Kind.TENANT_WRITE_REFUSED);
            }
        } finally {
            threads.shutdownNow();
            com.xavier.backend.OwnerDatabase.shared().jdbc().sql("DELETE FROM " + scratch.table()
                    + " WHERE label IN ('own', 'other')").update(); // the other cases count this table's rows
        }
    }

    private List<Object> writeOwnThenOther(UUID own, UUID other, CyclicBarrier bothOpen) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        StoreScopes stores = storeProvider.getObject();
        // a worker thread, as a listener of store A of its tenant: no fixture fallback
        Object written = TenantTestSupport.inTenant(tenants, own, () -> stores.callForStore(STORE_A, "test:worker",
                () -> tx.execute(status -> {
                    int rows = jdbc.sql("INSERT INTO " + scratch.table() + " (id, store_id, label) "
                            + "VALUES (?, ?, 'own')").param(UUID.randomUUID()).param(STORE_A).update();
                    await(bothOpen);
                    return rows;
                })));
        Object refused;
        try {
            TenantTestSupport.inTenant(tenants, own, () -> stores.callForStore(STORE_A, "test:worker",
                    () -> tx.execute(status -> jdbc.sql("INSERT INTO " + scratch.table()
                            + " (id, tenant_id, store_id, label) VALUES (?, ?, ?, 'other')").param(UUID.randomUUID())
                            .param(other).param(STORE_A).update())));
            refused = "written";
        } catch (RuntimeException e) {
            refused = StoreScopeViolations.classify(e).orElse(null);
        }
        return List.of(written, refused);
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * S77 (F1): a startup-only scope, so no scheduled job of this context begins one at the same time; on its own
     * thread, as a job runs (the test thread carries the test's mock request, so it would count as a request).
     */
    @Test
    void oneBeginCountsOneTransactionUnderItsOriginKindAndNoTenant() throws Exception {
        TransactionCount count = new TransactionCount(meters);
        Map<String, Long> before = count.snapshot();

        CompletableFuture.runAsync(() -> tenants.run(TenantTestSupport.A, SystemScope.REPORTING_RECOVERY,
                () -> new TransactionTemplate(transactions).executeWithoutResult(status -> pid())))
                .get(30, TimeUnit.SECONDS);

        assertThat(count.since(before)).containsEntry("system:REPORTING_RECOVERY", 1L);
        assertThat(meters.find(TenantBindingTransactionManager.TRANSACTIONS).counters()).isNotEmpty()
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .extracting(io.micrometer.core.instrument.Tag::getKey).containsExactly("origin"));
    }

    @Test
    void theTenantCannotChangeInsideATransaction() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                tenants.run(TenantTestSupport.B, SystemScope.ALERTS, () -> settings())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("inside a transaction");
    }

    private Map<String, Object> settings() {
        return jdbc.sql("SELECT current_setting('app.tenant_id', true) AS tenant, "
                + "current_setting('app.all_stores', true) AS all_stores, "
                + "current_setting('app.store_ids', true) AS store_ids, "
                + "current_setting('app.scope_origin', true) AS origin").query().singleRow();
    }

    private int pid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private List<String> labels() {
        return jdbc.sql("SELECT label FROM " + scratch.table() + " ORDER BY label").query(String.class).list();
    }

    /** Every physical connection of the pool (both held at once), its pid → its app.tenant_id, in autocommit. */
    private Map<Integer, String> everyPoolConnection() throws SQLException {
        Map<Integer, String> tenantByPid = new java.util.HashMap<>();
        try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection()) {
            for (Connection connection : List.of(first, second)) {
                try (ResultSet row = connection.createStatement().executeQuery(
                        "SELECT pg_backend_pid(), current_setting('app.tenant_id', true)")) {
                    row.next();
                    tenantByPid.put(row.getInt(1), row.getString(2));
                }
            }
        }
        return tenantByPid;
    }
}
