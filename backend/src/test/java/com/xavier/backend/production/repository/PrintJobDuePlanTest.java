package com.xavier.backend.production.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.dining.AppRoleSession;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * D169/D171 plan of the print worker's due work under row-level security: {@link PrintJobRepository#lockDue}, read
 * from the repository's own annotation, as the application role in the worker's all-stores scope
 * ({@code PRINT_WORKER}, D166). The policy's InitPlan does not stop the due jobs being found through the partial index
 * {@code print_job_due_ix} (only QUEUED and RETRY_WAIT jobs), not the whole job history. Deterministic seed: 4 000 SENT
 * jobs on a HOLD printer of a fresh store (never due work for another test's worker), then {@code ANALYZE} as the
 * owner and {@code EXPLAIN} in one transaction with {@code enable_seqscan = off}.
 */
class PrintJobDuePlanTest {

    static AppRoleSession app;

    @BeforeAll
    static void seed() throws SQLException {
        UUID store = UUID.randomUUID();
        UUID printer = ProductionRowLevelSecurityTest.printer(OwnerDatabase.shared().jdbc(), store);
        UUID ticket = UUID.randomUUID();
        OwnerDatabase.shared().tx(owner -> {
            owner.sql("""
                    INSERT INTO production.production_ticket (id, store_id, source_type, source_id, destination, kind,
                        printer_id, suppressed, content, created_at, routing, content_format)
                    VALUES (?, ?, 'BATCH', ?, 'PLAN', 'ORIGINAL', ?, false, 'plan seed', now(), 'ROUTED', 'PLAIN')""")
                    .param(ticket).param(store).param(UUID.randomUUID()).param(printer).update();
            owner.sql("""
                    INSERT INTO production.print_job (id, store_id, ticket_id, printer_id, kind, status, attempts,
                        next_attempt_at, started_at, sent_at, dedupe_key, created_at, version)
                    SELECT uuidv7(), :store, :ticket, :printer, 'ORIGINAL', 'SENT', 1, now(), now(), now(),
                        'due-plan:' || gen_random_uuid(), now() - interval '2 days', 0
                    FROM generate_series(1, 4000)""")
                    .param("store", store).param("ticket", ticket).param("printer", printer).update();
        });
        app = AppRoleSession.open().allStores();
    }

    @AfterAll
    static void close() throws SQLException {
        app.close();
    }

    @Test
    void theDueJobsAreFoundThroughThePartialDueIndex() throws Exception {
        String lockDue = PrintJobRepository.class.getMethod("lockDue", Instant.class, int.class)
                .getAnnotation(Query.class).value();
        OwnerDatabase.shared().jdbc().sql("ANALYZE production.print_job").update();

        String plan = app.inTransaction(jdbc -> {
            jdbc.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", jdbc.sql("EXPLAIN " + lockDue).param("now", Timestamp.from(Instant.now()))
                    .param("limit", 10).query(String.class).list());
        });

        assertThat(plan).as("the store_scope policy, once per statement").contains("InitPlan")
                .containsPattern("(Index Scan using|Index Only Scan using|Bitmap Index Scan on) "
                        + "print_job_due_ix");
    }
}
