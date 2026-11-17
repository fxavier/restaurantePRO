package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.AppRoleSession;
import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.StoreScopeTestSupport;
import com.xavier.backend.access.StrictStoreScope;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionFixtures.Round;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.settlement.model.event.ReceiptSlipRequested;
import com.xavier.backend.shared.tenancy.StrictTenantScope;
import com.xavier.backend.shared.tenancy.TenantTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A74 for production (D166): a production listener writes its ticket and job in its event's store scope, and the
 * print worker ({@code PRINT_WORKER}, all stores) claims the jobs of every store and hands them to its sender tasks,
 * which keep that scope on their own threads, under row-level security. The JUnit thread is strict (no actor, no
 * fixture fallback), as a scheduler thread is. The production module context boots {@code config}, so the
 * store-scope advisor and connection hook are those of production (its scheduled worker may send a job first: the
 * assertions wait for outcomes only); seeded and read as the owner.
 */
@ProductionModuleTest
@StrictStoreScope
@StrictTenantScope
class ProductionActorlessScopeTest {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    PrintWorker worker;

    @Autowired
    ProductionFixtures fixtures;

    final JdbcClient owner = OwnerDatabase.shared().jdbc();

    static final UUID T1 = UUID.fromString("0190b400-0000-7000-8000-0000000000d7");
    static final UUID T2 = UUID.fromString("0190b400-0000-7000-8000-0000000000d8");

    @BeforeEach
    void activeTenants() {
        TenantTestSupport.ensureTenant(T1, "production-1", "ACTIVE");
        TenantTestSupport.ensureTenant(T2, "production-2", "ACTIVE");
    }

    /** Back to no ACTIVE test tenant: the other suites' jobs iterate the bootstrap tenant only. */
    @AfterEach
    void closeTenants() {
        TenantTestSupport.ensureTenant(T1, "production-1", "CLOSED");
        TenantTestSupport.ensureTenant(T2, "production-2", "CLOSED");
    }

    /**
     * R6 (D185): a listener runs in its event's tenant, so the ticket and job of a tenant's receipt slip carry that
     * tenant, and the worker, run once per active tenant, sends each tenant's job on a sender thread that keeps the
     * tenant (an unbound sender could not record the send).
     */
    @Test
    void theReceiptSlipsOfTwoTenantsAreQueuedAndSentEachInItsOwnTenant() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.accepting()) {
            Map<UUID, UUID> paymentOf = Map.of(T1, UUID.randomUUID(), T2, UUID.randomUUID());
            paymentOf.forEach((tenant, payment) -> {
                UUID store = UUID.randomUUID();
                UUID terminal = terminalWithPrinter(tenant, store, printer);
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> events.publishEvent(
                        new ReceiptSlipRequested(tenant, payment, store, terminal, List.of("SLIP " + payment))));
            });
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> jobsOf(List.copyOf(paymentOf.values()))
                    .size() == 2);

            worker.poll();

            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> jobsOf(List.copyOf(paymentOf.values()))
                    .values().stream().allMatch("SENT"::equals));
            paymentOf.forEach((tenant, payment) -> assertThat(owner.sql("""
                            SELECT t.tenant_id = :tenant AND j.tenant_id = :tenant
                            FROM production.production_ticket t JOIN production.print_job j ON j.ticket_id = t.id
                            WHERE t.source_type = 'RECEIPT_SLIP' AND t.source_id = :payment""")
                    .param("tenant", tenant).param("payment", payment).query(Boolean.class).single())
                    .as("the ticket and job of %s carry its tenant", tenant).isTrue());
        }
    }

    @Test
    void aReceiptSlipOfEachStoreIsQueuedInItsStoreAndSentByTheWorker() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.accepting()) {
            List<UUID> payments = List.of(UUID.randomUUID(), UUID.randomUUID());
            for (UUID payment : payments) {
                UUID store = UUID.randomUUID();
                UUID terminal = terminalWithPrinter(store, printer);
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> events.publishEvent(
                        new ReceiptSlipRequested(TenantTestSupport.BOOTSTRAP,
                                payment, store, terminal, List.of("SLIP " + payment))));
            }
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> jobsOf(payments).size() == 2);

            worker.poll();

            Awaitility.await().atMost(Duration.ofSeconds(20))
                    .until(() -> jobsOf(payments).values().stream().allMatch("SENT"::equals));
            assertThat(printer.matching(StandardCharsets.ISO_8859_1, text -> text.contains("SLIP "))).hasSize(2);
        }
    }

    /**
     * A round confirmed over HTTP: the OrderBatchConfirmed listener prints the bar ticket in the round's store and
     * marks the batch dispatched through OrdersProductionApi (orders.order_batch), on its own listener thread.
     */
    @Test
    void aRoundIsTicketedAndDispatchedInItsStoreAndSentByTheWorker() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.accepting()) {
            UUID employee = UUID.randomUUID();
            UUID terminal = UUID.randomUUID();
            Round round = asFixture(() -> {
                Shop shop = fixtures.shop();
                fixtures.barPrinter(shop.store(), printer.host(), printer.port());
                return fixtures.confirmedRound(shop, employee, terminal, "1");
            });
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "DISPATCHED".equals(owner.sql(
                    "SELECT status FROM orders.order_batch WHERE id = ?").param(round.batch()).query(String.class)
                    .single()));

            worker.poll();

            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> owner.sql("""
                            SELECT count(*) FROM production.production_ticket t
                                JOIN production.print_job j ON j.ticket_id = t.id
                            WHERE t.source_type = 'BATCH' AND t.source_id = ? AND j.status = 'SENT'""")
                    .param(round.batch()).query(Long.class).single() == 1);
            assertThat(owner.sql("SELECT store_id FROM production.production_ticket WHERE source_id = ?")
                    .param(round.batch()).query(UUID.class).single()).isEqualTo(owner.sql(
                    "SELECT store_id FROM orders.order_batch WHERE id = ?").param(round.batch()).query(UUID.class)
                    .single());
        }
    }

    /**
     * Fixture setup on the strict thread in the test-only fixture scope: an actor alone does not last, since each
     * MockMvc request of the fixtures clears the security context, and their direct SQL (the catalog release) would
     * then be unset. The scope ends with the setup, so the test body is actor-less and unscoped again.
     */
    private <T> T asFixture(Callable<T> setup) {
        return StoreScopeTestSupport.inStrictFixture(() -> {
            try {
                return setup.call();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private Map<UUID, String> jobsOf(List<UUID> payments) {
        return owner.sql("""
                        SELECT t.source_id, j.status FROM production.production_ticket t
                            JOIN production.print_job j ON j.ticket_id = t.id
                        WHERE t.source_type = 'RECEIPT_SLIP' AND t.source_id IN (?, ?)""")
                .param(payments.get(0)).param(payments.get(1))
                .query((row, n) -> Map.entry(row.getObject(1, UUID.class), row.getString(2))).list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private UUID terminalWithPrinter(UUID store, TcpFakePrinter fake) {
        return terminalWithPrinter(TenantTestSupport.BOOTSTRAP, store, fake);
    }

    /** A printer and a terminal of {@code tenant}'s store, seeded as that tenant's application role. */
    private UUID terminalWithPrinter(UUID tenant, UUID store, TcpFakePrinter fake) {
        UUID printer = UUID.randomUUID();
        UUID terminal = UUID.randomUUID();
        try (AppRoleSession app = AppRoleSession.open().tenant(tenant).allStores()) {
            app.jdbc().sql("""
                    INSERT INTO production.printer (id, store_id, code, name, host, port, code_page, active, mode,
                        version, created_at, updated_at)
                    VALUES (?, ?, 'TILL', 'Till', ?, ?, 'CP860', true, 'NORMAL', 0, now(), now())""")
                    .param(printer).param(store).param(fake.host()).param(fake.port()).update();
            app.jdbc().sql("INSERT INTO production.terminal_printer (terminal_id, store_id, printer_id) "
                    + "VALUES (?, ?, ?)").param(terminal).param(store).param(printer).update();
        }
        return terminal;
    }
}
