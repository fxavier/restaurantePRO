package com.xavier.backend.production.controller;

import static com.xavier.backend.fiscal.FiscalActors.inStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.production.DrawerKicks;
import com.xavier.backend.production.DrawerKicks.Till;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.production.service.DrawerKickService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D158 (amended 2026-11-13, S60): a drawer kick is never repeated through the print queue. Retrying a FAILED or
 * UNKNOWN kick job (POS or BackOffice) and reprinting a kick ticket are 422 {@code KICK_NOT_REPEATABLE}: the drawer
 * opens again only through the manual open command ({@code CASH_OPERATE}, a reason, audited). Nothing new is queued.
 */
@ProductionModuleTest
class KickNotRepeatableTest {

    static final String DETAIL = "A cash drawer opens only through the manual open command.";

    @Autowired
    MockMvc mvc;

    @Autowired
    DrawerKicks kicks;

    @Autowired
    DrawerKickService service;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    FiscalFixtures stores;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Test
    void aFailedKickIsNotRetriedFromThePos() throws Exception {
        Till till = till();
        UUID job = kickIn(till, "FAILED");

        refused(mvc.perform(post("/api/v1/pos/print-jobs/" + job + "/retry").with(pos(till)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())));

        assertNothingQueuedAfter(till, job);
    }

    @Test
    void anUnknownKickIsNotRetriedFromTheBackOffice() throws Exception {
        Till till = till();
        UUID job = kickIn(till, "UNKNOWN");

        refused(mvc.perform(post("/api/v1/backoffice/print-jobs/" + job + "/retry")
                .with(inStore(till.store(), Permission.PRODUCTION_OPERATE)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())));

        assertNothingQueuedAfter(till, job);
    }

    @Test
    void aKickTicketIsNotReprinted() throws Exception {
        Till till = till();
        UUID job = kickIn(till, "FAILED");
        UUID ticket = (UUID) fixtures.job(job).get("ticket_id");

        refused(mvc.perform(post("/api/v1/pos/production-tickets/" + ticket + "/reprints").with(pos(till))
                .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())));

        assertNothingQueuedAfter(till, job);
    }

    private Till till() throws Exception {
        return kicks.till(stores.newStore(), "127.0.0.1", TcpFakePrinter.closedPort(), DrawerKicks.DRAWER);
    }

    /** A cash payment's kick on the till, put in {@code status} in the same transaction (no worker sees it). */
    private UUID kickIn(Till till, String status) {
        UUID payment = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            service.kick("PAYMENT", payment, till.store(), till.terminal(), Instant.now());
            jdbc.sql("UPDATE production.print_job SET status = ?, attempts = 1, started_at = now(), "
                            + "last_error = 'NOT_CONNECTED' WHERE ticket_id IN "
                            + "(SELECT id FROM production.production_ticket WHERE source_id = ?)")
                    .param(status).param(payment).update();
        });
        return kicks.job(payment);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor pos(Till till) {
        return OrdersActors.pos(till.store(), UUID.randomUUID(), till.terminal(), Permission.PRODUCTION_OPERATE);
    }

    private static void refused(ResultActions result) throws Exception {
        result.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("KICK_NOT_REPEATABLE"))
                .andExpect(jsonPath("$.detail").value(DETAIL));
    }

    private void assertNothingQueuedAfter(Till till, UUID job) {
        assertThat(jdbc.sql("SELECT count(*) FROM production.print_job WHERE store_id = ?").param(till.store())
                .query(Long.class).single()).isEqualTo(1);
        assertThat(fixtures.job(job)).containsEntry("resolution", null);
    }
}
