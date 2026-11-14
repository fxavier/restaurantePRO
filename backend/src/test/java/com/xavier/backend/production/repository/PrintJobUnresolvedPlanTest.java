package com.xavier.backend.production.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D150 {@code PRINT_JOBS_ATTENTION} polls {@link PrintJobRepository#countUnresolvedSince} through the alert cache, so
 * its scan must stay on the partial index {@code print_job_unresolved_ix} (only unresolved FAILED or UNKNOWN jobs),
 * not on the whole job history. Deterministic: a fixed seed of 4 000 sent jobs and 40 failed ones (half resolved),
 * then {@code ANALYZE} and {@code EXPLAIN} of the query's SQL in one transaction with {@code enable_seqscan = off},
 * asserting the index node. The failed jobs are two days old, so no alert of another test sees them.
 */
@ProductionModuleTest
class PrintJobUnresolvedPlanTest {

    /**
     * The SQL of {@code countUnresolvedSince}, translated by hand from its JPQL (enum literals inline); kept in step
     * with the JPQL by {@link #theRepositoryQueryKeepsThePartialIndexPredicate()}.
     */
    static final String UNRESOLVED = """
            SELECT j.store_id, count(j.id), min(j.created_at)
            FROM production.print_job j JOIN production.production_ticket t ON t.id = j.ticket_id
            WHERE t.source_type <> 'DRAWER_KICK' AND j.status IN ('FAILED', 'UNKNOWN')
              AND j.resolution IS NULL AND j.created_at >= :since
            GROUP BY j.store_id""";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Test
    void theUnresolvedJobsAreReadThroughThePartialIndex() throws Exception {
        seed();

        String plan = tx.execute(status -> {
            jdbc.sql("ANALYZE production.print_job").update();
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            List<String> lines = jdbc.sql("EXPLAIN " + UNRESOLVED)
                    .param("since", Timestamp.from(Instant.now().minus(Duration.ofHours(24)))).query(String.class)
                    .list();
            return String.join("\n", lines);
        });

        assertThat(plan).containsPattern("(Index Only Scan using|Index Scan using|Bitmap Index Scan on) "
                + "print_job_unresolved_ix");
    }

    /**
     * {@link #UNRESOLVED} is a hand translation of the JPQL: this pins the repository query to the partial index's
     * predicate (and to the store and window columns the plan assumes), so changing the JPQL breaks this test.
     */
    @Test
    void theRepositoryQueryKeepsThePartialIndexPredicate() throws Exception {
        String jpql = PrintJobRepository.class.getMethod("countUnresolvedSince", Instant.class)
                .getAnnotation(Query.class).value().replaceAll("\\s+", " ");

        assertThat(jpql).contains("j.status in (com.xavier.backend.production.model.PrintJobStatus.FAILED, "
                        + "com.xavier.backend.production.model.PrintJobStatus.UNKNOWN)")
                .contains("j.resolution is null").contains("j.createdAt >= :since").contains("group by j.storeId")
                .contains("t.sourceType <> com.xavier.backend.production.model.TicketSource.DRAWER_KICK");
    }

    private void seed() throws Exception {
        UUID store = stores.newStore();
        UUID printer = fixtures.printer(store, "P" + UUID.randomUUID().toString().substring(0, 6), "127.0.0.1",
                TcpFakePrinter.closedPort());
        UUID ticket = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO production.production_ticket (id, store_id, source_type, source_id, destination, kind,
                    printer_id, suppressed, content, created_at, routing, content_format)
                VALUES (?, ?, 'BATCH', ?, 'PLAN', 'ORIGINAL', ?, false, 'plan seed', now(), 'ROUTED', 'PLAIN')""")
                .param(ticket).param(store).param(UUID.randomUUID()).param(printer).update();
        jdbc.sql("""
                INSERT INTO production.print_job (id, store_id, ticket_id, printer_id, kind, status, attempts,
                    next_attempt_at, started_at, sent_at, last_error, resolution, dedupe_key, created_at, version)
                SELECT uuidv7(), :store, :ticket, :printer, 'ORIGINAL',
                    CASE WHEN n <= 4000 THEN 'SENT' ELSE 'FAILED' END, 1, now(), now() - interval '2 days',
                    CASE WHEN n <= 4000 THEN now() - interval '2 days' END,
                    CASE WHEN n > 4000 THEN 'NOT_CONNECTED' END,
                    CASE WHEN n > 4020 THEN 'RETRIED' END,
                    'plan:' || gen_random_uuid(), now() - interval '2 days', 0
                FROM generate_series(1, 4040) AS n""")
                .param("store", store).param("ticket", ticket).param("printer", printer).update();
    }
}
