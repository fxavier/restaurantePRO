package com.xavier.backend.production.service;

import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.access.SystemScope;
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
    StoreScopes scopes;

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

            // building and writing the 100 000-line printout until the write watchdog fires is slow on a loaded host
            fixtures.runWorkerUntil(() -> "UNKNOWN".equals(fixtures.jobStatus(job)), "job unknown",
                    java.time.Duration.ofSeconds(90));

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
        int attemptsWhenIgnored = (int) fixtures.job(job).get("attempts");
        for (int run = 0; run < 3; run++) {
            fixtures.dueNow(job);
            worker.poll();
            Thread.sleep(200);
        }

        assertThat(fixtures.job(job)).containsEntry("status", "RETRY_WAIT")
                .containsEntry("attempts", attemptsWhenIgnored);
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

    /**
     * D46/D50: a claim never waits for, nor takes, a job another transaction has locked. The job is created on a
     * HOLD printer and locked before the printer is released, so no worker (of this or another cached context) can
     * take it first: the only way the claim below gets it, or blocks on it, is a claim without SKIP LOCKED.
     */
    @Test
    void overlappingClaimsSkipEachOthersLockedJobs() throws Exception {
        UUID bar = fixtures.barPrinter(shop.store(), "127.0.0.1", TcpFakePrinter.closedPort());
        fixtures.mode(shop.store(), bar, "HOLD");
        UUID job = awaitJob(fixtures.confirmedRound(shop, employee, terminal, "1"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // a request of the store holding the job, on a thread of its own (no fixture fallback there)
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> scopes.callForStore(shop.store(),
                "test:holder", () -> tx.execute(s -> {
                    jdbc.sql("SELECT id FROM production.print_job WHERE id = ? FOR UPDATE").param(job).query()
                            .listOfRows();
                    locked.countDown();
                    waitFor(release);
                    return null;
                })));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            fixtures.mode(shop.store(), bar, "NORMAL"); // the locked job is due for every claimer now

            List<Dispatch> claimed = CompletableFuture.supplyAsync(() -> scopes.call(SystemScope.PRINT_WORKER,
                    () -> queue.claimDue(PrintWorker.MAX_CLAIM))).get(5, TimeUnit.SECONDS);

            assertThat(claimed).extracting(Dispatch::jobId).doesNotContain(job);
            assertThat(fixtures.jobStatus(job)).isEqualTo("QUEUED");
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * D50 §7.2 (amended): a claim takes the oldest due job of each printer, so a backlog on one printer never crowds
     * another printer out. The printers turn NORMAL only inside the claim's own (rolled back) transaction, so no
     * background worker of another context can take these jobs.
     */
    @Test
    void aBacklogOnOnePrinterDoesNotKeepAnotherPrintersJobFromBeingClaimed() throws Exception {
        UUID crowded = fixtures.barPrinter(shop.store(), printer.host(), printer.port());
        fixtures.mode(shop.store(), crowded, "HOLD");
        UUID oldest = awaitJob(fixtures.confirmedRound(shop, employee, terminal, "1"));
        UUID other = fixtures.printer(shop.store(), "OT" + UUID.randomUUID().toString().substring(0, 6),
                printer.host(), printer.port());
        fixtures.mode(shop.store(), other, "HOLD");
        jdbc.sql("UPDATE production.print_job SET created_at = timestamptz '2000-01-01 00:00:00Z' WHERE id = ?")
                .param(oldest).update();
        for (int i = 1; i <= 3; i++) {
            copyJob(oldest, crowded, "2000-01-01 00:00:0" + i + "Z");
        }
        UUID lone = copyJob(oldest, other, "2000-01-01 00:01:00Z");

        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.sql("UPDATE production.printer SET mode = 'NORMAL' WHERE id IN (?, ?)").param(crowded).param(other)
                    .update();

            List<Dispatch> claimed = queue.claimDue(2);

            assertThat(claimed).extracting(Dispatch::jobId).containsExactlyInAnyOrder(oldest, lone);
        });
    }

    /** D50 §7.2 (amended): a printer's head job blocks it while it waits to retry; it is still printed first. */
    @Test
    void aWaitingHeadJobKeepsNewerJobsOfItsPrinterBehindIt() throws Exception {
        UUID bar = fixtures.barPrinter(shop.store(), printer.host(), printer.port());
        fixtures.mode(shop.store(), bar, "HOLD");
        UUID head = awaitJob(fixtures.confirmedRound(shop, employee, terminal, "1"));
        UUID newer = copyJob(head, bar, "2000-01-01 00:00:01Z");

        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.sql("""
                    UPDATE production.print_job SET status = 'RETRY_WAIT', attempts = 1, started_at = now(),
                        next_attempt_at = now() + interval '1 hour', created_at = timestamptz '2000-01-01 00:00:00Z'
                    WHERE id = ?""").param(head).update();
            jdbc.sql("UPDATE production.printer SET mode = 'NORMAL' WHERE id = ?").param(bar).update();

            assertThat(queue.claimDue(PrintWorker.MAX_CLAIM)).extracting(Dispatch::printerId)
                    .doesNotContain(bar);

            jdbc.sql("UPDATE production.print_job SET next_attempt_at = now() - interval '1 second' WHERE id = ?")
                    .param(head).update();
            assertThat(queue.claimDue(PrintWorker.MAX_CLAIM)).extracting(Dispatch::jobId)
                    .contains(head).doesNotContain(newer);
        });
    }

    /**
     * D50 §7.2 (amended): one job in flight per printer across workers. A send another worker (another context or
     * instance on this database) has in flight keeps the printer's next job waiting, whatever this JVM knows.
     */
    @Test
    void aSendInFlightElsewhereKeepsThePrintersNextJobWaiting() throws Exception {
        UUID bar = fixtures.barPrinter(shop.store(), printer.host(), printer.port());
        fixtures.mode(shop.store(), bar, "HOLD");
        UUID sending = awaitJob(fixtures.confirmedRound(shop, employee, terminal, "1"));
        UUID next = copyJob(sending, bar, "2000-01-01 00:00:01Z");

        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.sql("UPDATE production.print_job SET status = 'PROCESSING', attempts = 1, started_at = now() WHERE id = ?")
                    .param(sending).update();
            jdbc.sql("UPDATE production.printer SET mode = 'NORMAL' WHERE id = ?").param(bar).update();

            assertThat(queue.claimDue(PrintWorker.MAX_CLAIM)).extracting(Dispatch::jobId)
                    .doesNotContain(next);
        });
    }

    /** Another due job for {@code printerId}, a copy of {@code job}'s ticket created at {@code createdAt}. */
    private UUID copyJob(UUID job, UUID printerId, String createdAt) {
        UUID copy = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO production.print_job (id, store_id, ticket_id, printer_id, kind, status, attempts,
                    next_attempt_at, dedupe_key, created_at, version)
                SELECT ?, store_id, ticket_id, ?, kind, 'QUEUED', 0, now() - interval '1 second', ?,
                    CAST(? AS timestamptz), 0
                FROM production.print_job WHERE id = ?""")
                .param(copy).param(printerId).param("test:" + copy).param(createdAt).param(job).update();
        return copy;
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
