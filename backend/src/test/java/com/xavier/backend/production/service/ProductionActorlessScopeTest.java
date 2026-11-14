package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.TestcontainersConfiguration;
import com.xavier.backend.access.StrictStoreScope;
import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.settlement.model.event.ReceiptSlipRequested;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A74 for production (D166): a production listener writes its ticket and job in its event's store scope, and the
 * print worker ({@code PRINT_WORKER}, all stores) claims the jobs of every store and hands them to its sender tasks,
 * which keep that scope on their own threads, under row-level security. The JUnit thread is strict (no actor, no
 * fixture fallback), as a scheduler thread is. The whole application, so the store-scope advisor and connection hook
 * are those of production (its own scheduled worker may send a job first: the assertions wait for outcomes only);
 * seeded and read as the owner.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@StrictStoreScope
class ProductionActorlessScopeTest {

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    PrintWorker worker;

    final JdbcClient owner = OwnerDatabase.shared().jdbc();

    @Test
    void aReceiptSlipOfEachStoreIsQueuedInItsStoreAndSentByTheWorker() throws Exception {
        try (TcpFakePrinter printer = TcpFakePrinter.accepting()) {
            List<UUID> payments = List.of(UUID.randomUUID(), UUID.randomUUID());
            for (UUID payment : payments) {
                UUID store = UUID.randomUUID();
                UUID terminal = terminalWithPrinter(store, printer);
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> events.publishEvent(
                        new ReceiptSlipRequested(payment, store, terminal, List.of("SLIP " + payment))));
            }
            Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> jobsOf(payments).size() == 2);

            worker.poll();

            Awaitility.await().atMost(Duration.ofSeconds(20))
                    .until(() -> jobsOf(payments).values().stream().allMatch("SENT"::equals));
            assertThat(printer.matching(StandardCharsets.ISO_8859_1, text -> text.contains("SLIP "))).hasSize(2);
        }
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
        UUID printer = UUID.randomUUID();
        UUID terminal = UUID.randomUUID();
        owner.sql("""
                INSERT INTO production.printer (id, store_id, code, name, host, port, code_page, active, mode,
                    version, created_at, updated_at)
                VALUES (?, ?, 'TILL', 'Till', ?, ?, 'CP860', true, 'NORMAL', 0, now(), now())""")
                .param(printer).param(store).param(fake.host()).param(fake.port()).update();
        owner.sql("INSERT INTO production.terminal_printer (terminal_id, store_id, printer_id) VALUES (?, ?, ?)")
                .param(terminal).param(store).param(printer).update();
        return terminal;
    }
}
