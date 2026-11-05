package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalActors;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionFixtures.Round;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.production.model.CodePage;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The print queue against a real TCP printer (D50 §7.2, D57, spec §4): a listening printer gets the ticket (SENT); a
 * printer that is down leaves the job waiting with backoff and then FAILED, with the order untouched, and a retry
 * prints it once (A5); a send that hangs, or one lost in a crash (lease), is UNKNOWN and is never resent on its own;
 * HOLD keeps jobs; REDIRECT moves them; overlapping claims skip each other's locked jobs.
 */
@ProductionModuleTest
class PrintQueueTest {

    static final Charset CP860 = CodePage.CP860.charset();

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    PrintWorker worker;

    @Autowired
    PrintQueue queue;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MeterRegistry meters;

    @Autowired
    ChangeNotifier notifier;

    final UUID employee = UUID.randomUUID();
    /** Per test: the test instance is shared by the class's methods, and a terminal prints in one store. */
    UUID terminal;
    Shop shop;
    TcpFakePrinter printer;

    @BeforeEach
    void shop() throws Exception {
        terminal = UUID.randomUUID();
        shop = fixtures.shop();
        printer = TcpFakePrinter.accepting();
    }

    @AfterEach
    void stopPrinter() throws Exception {
        printer.close();
    }

    @Test
    void aListeningPrinterReceivesTheTicketAsEscPosAndTheJobIsSent() throws Exception {
        UUID bar = fixtures.barPrinter(shop.store(), printer.host(), printer.port());
        Round round = fixtures.confirmedRound(shop, employee, terminal, "2");
        UUID job = awaitJob(round);

        fixtures.runWorkerUntil(() -> "SENT".equals(fixtures.jobStatus(job)), "job sent");

        List<String> printed = printer.awaitPayloads(CP860, text -> text.contains("Pedido #" + round.orderNumber()),
                1, Duration.ofSeconds(5));
        assertThat(printed).singleElement().satisfies(text -> assertThat(text).startsWith("\u001b@\u001bt\u0003")
                .contains("BAR\n", "Ronda 1\n", "2 x Coca-Cola 33cl\n").endsWith("\u001dVB\u0000")
                .doesNotContain("CÓPIA"));
        assertThat(fixtures.job(job)).containsEntry("attempts", 1).containsEntry("printer_id", bar);
        assertThat(meters.get("print_jobs").tag("status", "SENT").gauge().value()).isPositive();
        // queued, then sent
        verify(notifier, timeout(5000).times(2)).notifyAfterCommit(eq(shop.store()), eq(ChangeTopic.PRINT_JOBS),
                eq(job));
    }

    /** A5: unplugged → RETRY_WAIT with backoff, then FAILED; the order is untouched; after a retry it prints once. */
    @Test
    void aPrinterThatIsDownKeepsTheJobUntilItFailsAndARetryPrintsItOnce() throws Exception {
        fixtures.barPrinter(shop.store(), printer.host(), printer.port());
        printer.down();
        Round round = fixtures.confirmedRound(shop, employee, terminal, "1");
        UUID job = awaitJob(round);

        fixtures.runWorkerUntil(() -> "RETRY_WAIT".equals(fixtures.jobStatus(job)), "job waiting to retry");
        Map<String, Object> waiting = fixtures.job(job);
        assertThat(waiting).containsEntry("attempts", 1).containsEntry("last_error", "NOT_CONNECTED");
        assertThat(((java.sql.Timestamp) waiting.get("next_attempt_at")).toInstant())
                .isAfter(((java.sql.Timestamp) waiting.get("started_at")).toInstant().plusMillis(1500));
        for (int attempt = 2; attempt <= 5; attempt++) {
            int expected = attempt;
            fixtures.dueNow(job);
            fixtures.runWorkerUntil(() -> (int) fixtures.job(job).get("attempts") >= expected
                    && !"PROCESSING".equals(fixtures.jobStatus(job)), "attempt " + attempt);
        }
        assertThat(fixtures.jobStatus(job)).isEqualTo("FAILED");
        assertThat(orderLifecycleAndVersion(round.order())).containsEntry("lifecycle", "OPEN")
                .containsEntry("version", round.orderVersion());
        assertThat(fixtures.batchStatus(round.batch())).isEqualTo("DISPATCHED");

        printer.up();
        UUID retry = ProductionFixtures.id(mvc.perform(post("/api/v1/backoffice/print-jobs/" + job + "/retry")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).with(csrf())
                        .with(FiscalActors.inStore(shop.store(), Permission.PRODUCTION_OPERATE)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.retryOf").value(job.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED")));
        fixtures.runWorkerUntil(() -> "SENT".equals(fixtures.jobStatus(retry)), "retry sent");

        assertThat(printer.awaitPayloads(CP860, text -> text.contains("Pedido #" + round.orderNumber()), 1,
                Duration.ofSeconds(2))).hasSize(1);
        assertThat(fixtures.tickets(round.batch())).hasSize(1);
        assertThat(fixtures.jobsOfSource(round.batch())).extracting(row -> row.get("status"))
                .containsExactly("FAILED", "SENT");
        assertThat(fixtures.job(job)).containsEntry("resolution", "RETRIED");
        assertThat(fixtures.audits("PRINT_JOB_RETRIED", job)).isEqualTo(1);
    }

    /** "Hangs after reading": the write cannot finish, part may have printed → UNKNOWN, not resent. */
    @Test
    void aSendThatCannotFinishIsUnknownAndIsNotResent() throws Exception {
        try (TcpFakePrinter hanging = TcpFakePrinter.hanging()) {
            UUID counter = fixtures.printer(shop.store(), "CX" + UUID.randomUUID().toString().substring(0, 6),
                    hanging.host(), hanging.port());
            fixtures.terminalPrinters(shop.store(), Map.of(terminal, counter)).andExpect(status().isOk());
            UUID document = UUID.randomUUID();
            List<String> longPrintout = java.util.stream.IntStream.range(0, 100_000)
                    .mapToObj(i -> "linha " + i + " ".repeat(70)).toList();
            tx.executeWithoutResult(s -> tickets.printDocument(document, shop.store(), terminal,
                    new com.xavier.backend.fiscal.model.event.DocumentPrintout(longPrintout, null), 0));
            UUID job = (UUID) fixtures.jobsOfSource(document).getFirst().get("id");

            fixtures.runWorkerUntil(() -> "UNKNOWN".equals(fixtures.jobStatus(job)), "job unknown");

            assertThat(fixtures.job(job)).containsEntry("last_error", "INTERRUPTED_AFTER_WRITE")
                    .containsEntry("attempts", 1);
            worker.poll();
            assertThat(fixtures.job(job)).containsEntry("status", "UNKNOWN").containsEntry("attempts", 1);
        }
    }

    @Autowired
    TicketService tickets;

    /** A crash during the send leaves PROCESSING: after the 60 s lease it is UNKNOWN; a person acknowledges it. */
    @Test
    void aJobLostDuringItsSendBecomesUnknownAfterTheLeaseAndCanBeAcknowledged() throws Exception {
        UUID bar = fixtures.barPrinter(shop.store(), printer.host(), printer.port());
        fixtures.mode(shop.store(), bar, "HOLD");
        Round round = fixtures.confirmedRound(shop, employee, terminal, "1");
        UUID job = awaitJob(round);
        jdbc.sql("""
                UPDATE production.print_job SET status = 'PROCESSING', attempts = 1,
                    started_at = now() - interval '61 seconds' WHERE id = ?""").param(job).update();

        fixtures.runWorkerUntil(() -> "UNKNOWN".equals(fixtures.jobStatus(job)), "lease expired");

        assertThat(fixtures.job(job)).containsEntry("last_error", "LEASE_EXPIRED").containsEntry("attempts", 1);
        mvc.perform(post("/api/v1/pos/print-jobs/" + job + "/acknowledge")
                        .header("Idempotency-Key", UUID.randomUUID().toString()).with(csrf())
                        .with(FiscalActors.pos(shop.store(), employee, terminal, Permission.PRODUCTION_OPERATE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.resolution").value("CONFIRMED_PRINTED"));
        assertThat(fixtures.audits("PRINT_JOB_ACKNOWLEDGED", job)).isEqualTo(1);
        assertThat(printer.matching(CP860, text -> text.contains("Pedido #" + round.orderNumber()))).isEmpty();
    }

    @Test
    void holdKeepsJobsQueuedUntilThePrinterIsNormalAgain() throws Exception {
        UUID bar = fixtures.barPrinter(shop.store(), printer.host(), printer.port());
        fixtures.mode(shop.store(), bar, "HOLD");
        Round round = fixtures.confirmedRound(shop, employee, terminal, "1");
        UUID job = awaitJob(round);

        worker.poll();
        worker.poll();
        assertThat(fixtures.job(job)).containsEntry("status", "QUEUED").containsEntry("attempts", 0);

        fixtures.mode(shop.store(), bar, "NORMAL");
        fixtures.runWorkerUntil(() -> "SENT".equals(fixtures.jobStatus(job)), "sent after hold");
    }

    /** Spec §8.6: ignoring is a suppression, not a failure; jobs already waiting stay pending and visible. */
    @Test
    void pendingJobsOfAnIgnoredPrinterAreNeitherSentNorFailed() throws Exception {
        UUID bar = fixtures.barPrinter(shop.store(), printer.host(), printer.port());
        printer.down();
        Round round = fixtures.confirmedRound(shop, employee, terminal, "1");
        UUID job = awaitJob(round);
        fixtures.runWorkerUntil(() -> "RETRY_WAIT".equals(fixtures.jobStatus(job)), "job waiting to retry");

        fixtures.mode(shop.store(), bar, "IGNORE");
        for (int run = 0; run < 3; run++) {
            fixtures.dueNow(job);
            worker.poll();
            Thread.sleep(200);
        }

        assertThat(fixtures.job(job)).containsEntry("status", "RETRY_WAIT").containsEntry("attempts", 1);
    }

    /** D56: REDIRECT moves undelivered jobs to the target, and new work goes there (fixed when the job is made). */
    @Test
    void redirectMovesPendingJobsAndSendsNewWorkToTheTarget() throws Exception {
        UUID bar = fixtures.barPrinter(shop.store(), "127.0.0.1", TcpFakePrinter.closedPort());
        fixtures.mode(shop.store(), bar, "HOLD");
        Round before = fixtures.confirmedRound(shop, employee, terminal, "1");
        UUID pending = awaitJob(before);
        UUID spare = fixtures.printer(shop.store(), "SPARE" + UUID.randomUUID().toString().substring(0, 4),
                printer.host(), printer.port());

        fixtures.changeMode(shop.store(), bar, "REDIRECT", spare, Permission.PRODUCTION_MANAGE)
                .andExpect(status().isOk()).andExpect(jsonPath("$.redirectPrinterId").value(spare.toString()));

        assertThat(fixtures.job(pending)).containsEntry("printer_id", spare);
        Round after = fixtures.nextRound(shop, before.order(), before.orderNumber(), before.orderVersion(), employee,
                terminal, "4");
        UUID redirected = awaitJob(after);
        assertThat(fixtures.tickets(after.batch()).getFirst()).containsEntry("printer_id", bar);
        assertThat(fixtures.job(redirected)).containsEntry("printer_id", spare);
        fixtures.runWorkerUntil(() -> "SENT".equals(fixtures.jobStatus(pending))
                && "SENT".equals(fixtures.jobStatus(redirected)), "both sent on the target");
        assertThat(fixtures.audits("PRINTER_MODE_CHANGED", bar)).isEqualTo(2);
    }

    /** D46/D50: a claim never waits for, nor takes, a job another claim has locked. */
    @Test
    void overlappingClaimsSkipEachOthersLockedJobs() throws Exception {
        fixtures.barPrinter(shop.store(), "127.0.0.1", TcpFakePrinter.closedPort());
        awaitJob(fixtures.confirmedRound(shop, employee, terminal, "1"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<List<Dispatch>> first = CompletableFuture.supplyAsync(() -> tx.execute(s -> {
            List<Dispatch> claimed = queue.claimDue(PrintWorker.MAX_CLAIM, Set.of());
            locked.countDown();
            waitFor(release);
            return claimed;
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        List<Dispatch> second = CompletableFuture.supplyAsync(() -> queue.claimDue(PrintWorker.MAX_CLAIM, Set.of()))
                .get(5, TimeUnit.SECONDS);
        release.countDown();

        List<UUID> firstIds = first.get(10, TimeUnit.SECONDS).stream().map(Dispatch::jobId).toList();
        assertThat(firstIds).isNotEmpty();
        assertThat(second).extracting(Dispatch::jobId).doesNotContainAnyElementsOf(firstIds);
    }

    private UUID awaitJob(Round round) throws InterruptedException {
        await(() -> !fixtures.jobsOfSource(round.batch()).isEmpty(), "job of batch " + round.batch());
        return (UUID) fixtures.jobsOfSource(round.batch()).getFirst().get("id");
    }

    private Map<String, Object> orderLifecycleAndVersion(UUID order) {
        return jdbc.sql("SELECT lifecycle, version FROM orders.order_header WHERE id = ?").param(order).query()
                .singleRow();
    }

    private static void waitFor(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
