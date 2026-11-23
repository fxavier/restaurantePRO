package com.xavier.backend.production.service;

import static com.xavier.backend.fiscal.FiscalActors.inStore;
import static com.xavier.backend.fiscal.FiscalActors.pos;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.production.ProductionModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The POS alerts production owns (light reference: "1 impressão pendente", "Cozinha: 2 rondas > 15 min"), for the
 * session's store and only with PRODUCTION_VIEW: the print jobs still waiting or failed without a resolution (a
 * drawer kick is never one, D158), and the rounds the kitchen has not made ready for longer than the configured delay.
 * Rows are written directly, on a printer on HOLD with a far due time, so no worker of another context moves them.
 */
@ProductionModuleTest
class ProductionAlertsTest {

    static final String ALERTS = "/api/v1/pos/production/alerts";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    UUID store;
    UUID printer;

    @BeforeEach
    void aStoreWithAHeldPrinter() {
        store = store();
        printer = printer(store);
    }

    @Test
    void anEmptyStoreHasNoAlertsAndNamesTheKitchenDelay() throws Exception {
        mvc.perform(get(ALERTS).with(viewer(store))).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingPrintJobs").value(0))
                .andExpect(jsonPath("$.delayedKitchenRounds").value(0))
                .andExpect(jsonPath("$.kitchenDelayMinutes").value(15));
    }

    @Test
    void waitingAndUnresolvedFailedJobsArePendingAndPrintedOrResolvedOnesAreNot() throws Exception {
        job(store, "QUEUED", null, "BATCH", "now()");
        job(store, "RETRY_WAIT", null, "BATCH", "now()");
        job(store, "PROCESSING", null, "BATCH", "now()");
        job(store, "FAILED", null, "BATCH", "now()");
        job(store, "UNKNOWN", null, "BATCH", "now()");
        job(store, "SENT", null, "BATCH", "now()");
        job(store, "UNKNOWN", "CONFIRMED_PRINTED", "BATCH", "now()");
        job(store, "FAILED", "RETRIED", "BATCH", "now()");
        job(store, "FAILED", null, "BATCH", "now() - interval '25 hours'");
        job(store, "FAILED", null, "DRAWER_KICK", "now()");

        mvc.perform(get(ALERTS).with(viewer(store))).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingPrintJobs").value(5));
    }

    @Test
    void roundsNotReadyForLongerThanTheDelayAreLate() throws Exception {
        round(store, "QUEUED", "now() - interval '20 minutes'");
        round(store, "IN_PREPARATION", "now() - interval '16 minutes'");
        round(store, "QUEUED", "now() - interval '5 minutes'");
        round(store, "READY", "now() - interval '40 minutes'");
        round(store, "BUMPED", "now() - interval '40 minutes'");

        mvc.perform(get(ALERTS).with(viewer(store))).andExpect(status().isOk())
                .andExpect(jsonPath("$.delayedKitchenRounds").value(2));
    }

    @Test
    void anotherStoresJobsAndRoundsAreNotCounted() throws Exception {
        UUID other = store();
        UUID otherPrinter = printer(other);
        job(other, otherPrinter, "FAILED", null, "BATCH", "now()");
        round(other, "QUEUED", "now() - interval '30 minutes'");

        mvc.perform(get(ALERTS).with(viewer(store))).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingPrintJobs").value(0))
                .andExpect(jsonPath("$.delayedKitchenRounds").value(0));
        mvc.perform(get(ALERTS).with(viewer(other))).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingPrintJobs").value(1))
                .andExpect(jsonPath("$.delayedKitchenRounds").value(1));
    }

    @Test
    void theAlertsNeedAPosSessionWithProductionView() throws Exception {
        mvc.perform(get(ALERTS).with(pos(store, employee, terminal, Permission.ORDER_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(ALERTS).with(inStore(store, Permission.PRODUCTION_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(ALERTS)).andExpect(status().isUnauthorized());
    }

    private RequestPostProcessor viewer(UUID inStore) {
        return pos(inStore, employee, terminal, Permission.PRODUCTION_VIEW);
    }

    private UUID store() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO organization.store (id, code, name, timezone, active, version, created_at, updated_at)
                VALUES (?, ?, 'Alertas', 'Africa/Maputo', true, 0, now(), now())""")
                .param(id).param("AL-" + id.toString().substring(0, 8)).update();
        return id;
    }

    private UUID printer(UUID inStore) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO production.printer (id, store_id, code, name, host, port, code_page, active, mode,
                    version, created_at, updated_at)
                VALUES (?, ?, ?, 'Bar', '127.0.0.1', 9100, 'CP860', true, 'HOLD', 0, now(), now())""")
                .param(id).param(inStore).param("P" + id.toString().substring(0, 6)).update();
        return id;
    }

    private void job(UUID inStore, String status, String resolution, String source, String createdAt) {
        job(inStore, printer, status, resolution, source, createdAt);
    }

    /** A job of a new ticket of {@code source}, due tomorrow, so no worker claims it. */
    private void job(UUID inStore, UUID onPrinter, String status, String resolution, String source,
                     String createdAt) {
        UUID ticket = ticket(inStore, source, "ROUTED", onPrinter);
        UUID id = UUID.randomUUID();
        boolean queued = status.equals("QUEUED");
        jdbc.sql("""
                INSERT INTO production.print_job (id, store_id, ticket_id, printer_id, kind, status, attempts,
                    next_attempt_at, started_at, sent_at, resolution, dedupe_key, created_at, version)
                VALUES (?, ?, ?, ?, 'ORIGINAL', ?, ?, now() + interval '1 day', %s, %s, ?, ?, %s, 0)"""
                        .formatted(queued ? "null" : "now()", status.equals("SENT") ? "now()" : "null", createdAt))
                .param(id).param(inStore).param(ticket).param(onPrinter).param(status).param(queued ? 0 : 1)
                .param(resolution).param("alerts:" + id).update();
    }

    /** A round on the kitchen display, queued at {@code queuedAt}, with the step times its status needs. */
    private void round(UUID inStore, String status, String queuedAt) {
        UUID ticket = ticket(inStore, "BATCH", "UNROUTED", null);
        boolean started = !status.equals("QUEUED");
        boolean ready = status.equals("READY") || status.equals("BUMPED");
        boolean bumped = status.equals("BUMPED");
        jdbc.sql("""
                INSERT INTO production.kitchen_progress (ticket_id, store_id, destination, status, version,
                    queued_at, started_at, started_by, ready_at, ready_by, bumped_at, bumped_by)
                VALUES (?, ?, 'BAR', ?, 0, %s, %s, %s, %s, %s, %s, %s)""".formatted(queuedAt,
                        started ? "now()" : "null", started ? "gen_random_uuid()" : "null",
                        ready ? "now()" : "null", ready ? "gen_random_uuid()" : "null",
                        bumped ? "now()" : "null", bumped ? "gen_random_uuid()" : "null"))
                .param(ticket).param(inStore).param(status).update();
    }

    private UUID ticket(UUID inStore, String source, String routing, UUID onPrinter) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO production.production_ticket (id, store_id, source_type, source_id, destination, kind,
                    printer_id, terminal_id, routing, suppressed, content, content_format, created_at)
                VALUES (?, ?, ?, ?, 'BAR', 'ORIGINAL', ?, ?, ?, false, 'alerts', ?, now())""")
                .param(id).param(inStore).param(source).param(UUID.randomUUID()).param(onPrinter).param(terminal)
                .param(routing).param(source.equals("DRAWER_KICK") ? "KICK" : "PLAIN").update();
        return id;
    }
}
