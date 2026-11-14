package com.xavier.backend.production.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.production.DrawerKicks;
import com.xavier.backend.production.DrawerKicks.Till;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.shared.ops.AlertSeverity;
import com.xavier.backend.shared.ops.OperationalAlert;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D150, A63 (print queue half): {@code PRINT_JOBS_ATTENTION} counts a store's FAILED or UNKNOWN jobs with no
 * resolution created in the last 24 h; {@code PRINT_QUEUE_STALLED} is a QUEUED or RETRY_WAIT job older than 5 min on
 * a NORMAL printer. Both are WARNINGs of the store, and the gauges {@code print_jobs_unresolved{store}} and
 * {@code print_jobs_oldest_waiting_seconds{store}} move with them. Drawer kicks count in neither (D158): a missed
 * kick is the cashier's to see, not a print alert. Jobs sit on HOLD printers, so no worker changes
 * them; when a printer turns NORMAL its jobs are not due before tomorrow, so no worker claims them either. The source
 * reads in its own system scope (D166), which cannot start inside a transaction: every change it must see is
 * committed.
 */
@ProductionModuleTest
class PrintQueueAlertSourceTest {

    @Autowired
    PrintQueueAlertSource source;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    MeterRegistry meters;

    @Autowired
    DrawerKicks kicks;

    @Autowired
    DrawerKickService drawerKicks;

    UUID store;
    UUID printer;

    @BeforeEach
    void heldPrinter() throws Exception {
        store = stores.newStore();
        printer = fixtures.printer(store, "H" + UUID.randomUUID().toString().substring(0, 6), "127.0.0.1",
                TcpFakePrinter.closedPort());
        fixtures.mode(store, printer, "HOLD");
    }

    @Test
    void unresolvedFailedAndUnknownJobsOfTheLast24HoursNeedAttention() throws Exception {
        UUID failed = job("FAILED", "1 hour", null);
        job("UNKNOWN", "10 minutes", null);
        job("FAILED", "2 hours", "RETRIED");
        job("UNKNOWN", "3 hours", "CONFIRMED_PRINTED");
        job("FAILED", "25 hours", null);

        assertThat(storeAlerts()).singleElement().satisfies(alert -> {
            assertThat(alert.code()).isEqualTo("PRINT_JOBS_ATTENTION");
            assertThat(alert.severity()).isEqualTo(AlertSeverity.WARNING);
            assertThat(alert.count()).isEqualTo(2);
            assertThat(alert.since()).isEqualTo(createdAt(failed));
            assertThat(alert.summary()).doesNotContain(failed.toString());
        });
        assertThat(gauge("print_jobs_unresolved")).isEqualTo(2);
    }

    @Test
    void theAlertClearsOnceTheJobsAreResolved() throws Exception {
        UUID failed = job("FAILED", "1 hour", null);
        assertThat(storeAlerts()).extracting(OperationalAlert::code).containsExactly("PRINT_JOBS_ATTENTION");

        jdbc.sql("UPDATE production.print_job SET resolution = 'RETRIED' WHERE id = ?").param(failed).update();

        assertThat(storeAlerts()).isEmpty();
        assertThat(gauge("print_jobs_unresolved")).isZero();
    }

    @Test
    void aJobWaitingOverFiveMinutesOnANormalPrinterIsAStalledQueue() throws Exception {
        UUID waiting = job("QUEUED", "6 minutes", null);
        assertThat(storeAlerts()).as("on HOLD, waiting is intended").isEmpty();

        normal(printer);

        assertThat(storeAlerts()).singleElement().satisfies(alert -> {
            assertThat(alert.code()).isEqualTo("PRINT_QUEUE_STALLED");
            assertThat(alert.severity()).isEqualTo(AlertSeverity.WARNING);
            assertThat(alert.count()).isEqualTo(1);
            assertThat(alert.since()).isEqualTo(createdAt(waiting));
        });
        assertThat(gauge("print_jobs_oldest_waiting_seconds")).isBetween(355.0, 3600.0);

        jdbc.sql("UPDATE production.print_job SET created_at = now() - interval '4 minutes' WHERE id = ?")
                .param(waiting).update();
        assertThat(storeAlerts()).as("4 minutes is not stalled").isEmpty();
        assertThat(gauge("print_jobs_oldest_waiting_seconds")).isBetween(235.0, 300.0);
    }

    @Test
    void aRetryWaitJobCountsAsWaiting() throws Exception {
        job("RETRY_WAIT", "7 minutes", null);

        normal(printer);

        assertThat(storeAlerts()).extracting(OperationalAlert::code).containsExactly("PRINT_QUEUE_STALLED");
    }

    @Test
    void drawerKicksNeverRaiseAPrintAlertNorMoveTheGauges() throws Exception {
        Till till = kicks.till(store, "127.0.0.1", TcpFakePrinter.closedPort(), DrawerKicks.DRAWER);
        fixtures.mode(store, till.printer(), "HOLD");
        tx.executeWithoutResult(status -> {
            UUID payment = kick(till, "1 hour");
            jdbc.sql("UPDATE production.print_job SET status = 'FAILED', started_at = now(), "
                    + "last_error = 'PRINTER_NOT_NORMAL' "
                    + "WHERE id = ?").param(kicks.job(payment)).update();
        });

        assertThat(storeAlerts()).as("an unresolved failed kick").isEmpty();
        assertThat(gaugeOrZero("print_jobs_unresolved")).isZero();

        // a worker may claim and fail this kick meanwhile (kicks are taken whatever is due): it never counts either
        normal(till.printer());
        tx.executeWithoutResult(status -> kick(till, "6 minutes"));

        assertThat(storeAlerts()).as("a kick waiting on a NORMAL printer").isEmpty();
        assertThat(gaugeOrZero("print_jobs_oldest_waiting_seconds")).isZero();
    }

    /** NORMAL in the database directly (no audit, no notice): its jobs stay not yet due, see {@link #job}. */
    private void normal(UUID printerId) {
        jdbc.sql("UPDATE production.printer SET mode = 'NORMAL' WHERE id = ?").param(printerId).update();
    }

    /** A cash payment's kick on the till, created {@code age} ago, in the caller's transaction; its source. */
    private UUID kick(Till till, String age) {
        UUID payment = UUID.randomUUID();
        drawerKicks.kick("PAYMENT", payment, till.store(), till.terminal(), Instant.now());
        jdbc.sql("""
                UPDATE production.print_job SET created_at = now() - CAST(? AS interval)
                WHERE ticket_id IN (SELECT id FROM production.production_ticket WHERE source_id = ?)""")
                .param(age).param(payment).update();
        return payment;
    }

    /** The store's gauge, or 0 while the store has never had a counted job. */
    private double gaugeOrZero(String name) {
        Gauge gauge = meters.find(name).tag("store", store.toString()).gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private List<OperationalAlert> storeAlerts() {
        return source.alerts().stream().filter(alert -> store.equals(alert.storeId())).toList();
    }

    private double gauge(String name) {
        return meters.get(name).tag("store", store.toString()).gauge().value();
    }

    /**
     * A job of a test ticket on the held printer, in {@code status}, created {@code age} ago, not due before
     * tomorrow (so no worker claims it once the printer is NORMAL).
     */
    private UUID job(String status, String age, String resolution) throws Exception {
        UUID job = fixtures.testPage(store, printer);
        jdbc.sql("""
                UPDATE production.print_job SET status = ?, attempts = CASE WHEN ? = 'QUEUED' THEN 0 ELSE 1 END,
                    started_at = CASE WHEN ? = 'QUEUED' THEN NULL ELSE now() END, resolution = ?,
                    created_at = now() - CAST(? AS interval), next_attempt_at = now() + interval '1 day'
                WHERE id = ?""").param(status).param(status).param(status).param(resolution).param(age).param(job)
                .update();
        return job;
    }

    private Instant createdAt(UUID job) {
        return ((Timestamp) fixtures.job(job).get("created_at")).toInstant();
    }
}
