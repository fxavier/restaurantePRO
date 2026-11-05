package com.xavier.backend.production.controller;

import static com.xavier.backend.fiscal.FiscalActors.inStore;
import static com.xavier.backend.fiscal.FiscalActors.pos;
import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.fiscal.FiscalActors;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionFixtures.Round;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.production.TcpFakePrinter;
import com.xavier.backend.production.adapter.EscPosRenderer;
import com.xavier.backend.production.model.CodePage;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The print queue endpoints: lists, retry, acknowledge, reprint; their states, scope (D39, D42) and keys. */
@ProductionModuleTest
class PrintJobHttpTest {

    static final String BACKOFFICE = "/api/v1/backoffice/print-jobs";
    static final String POS = "/api/v1/pos/print-jobs";

    @Autowired
    MockMvc mvc;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID bar;
    Round round;
    UUID job;

    /** A round whose job waits on a HOLD printer, so no worker touches it. */
    @BeforeEach
    void heldRound() throws Exception {
        shop = fixtures.shop();
        bar = fixtures.barPrinter(shop.store(), "127.0.0.1", 9);
        fixtures.mode(shop.store(), bar, "HOLD");
        round = fixtures.confirmedRound(shop, employee, terminal, "1");
        await(() -> !fixtures.jobsOfSource(round.batch()).isEmpty(), "job");
        job = (UUID) fixtures.jobsOfSource(round.batch()).getFirst().get("id");
    }

    @Test
    void theQueueIsListedPerStoreAndStatus() throws Exception {
        mvc.perform(get(BACKOFFICE).param("storeId", shop.store().toString()).param("status", "QUEUED")
                        .with(inStore(shop.store(), Permission.PRODUCTION_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(job.toString()))
                .andExpect(jsonPath("$.content[0].sourceType").value("BATCH"))
                .andExpect(jsonPath("$.content[0].orderId").value(round.order().toString()));
        mvc.perform(get(BACKOFFICE).param("storeId", shop.store().toString()).param("status", "FAILED")
                        .with(inStore(shop.store(), Permission.PRODUCTION_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get(BACKOFFICE).param("storeId", shop.store().toString()).param("from", "2999-01-01T00:00:00Z")
                        .with(inStore(shop.store(), Permission.PRODUCTION_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get(POS).with(pos(shop.store(), employee, terminal, Permission.PRODUCTION_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(job.toString()));
        mvc.perform(get(BACKOFFICE).param("storeId", shop.store().toString())
                .with(inStore(shop.store(), Permission.PRODUCTION_OPERATE))).andExpect(status().isForbidden());
        mvc.perform(get(POS).with(FiscalActors.admin(Permission.PRODUCTION_VIEW))).andExpect(status().isForbidden());
    }

    @Test
    void onlyFailedOrUnknownJobsAreRetriedAndOnlyOnce() throws Exception {
        retry(job, operator()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_PRINT_JOB_STATE"));
        fail(job);

        retry(job, operator()).andExpect(status().isCreated()).andExpect(jsonPath("$.retryOf").value(job.toString()))
                .andExpect(jsonPath("$.kind").value("ORIGINAL"));
        retry(job, operator()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRINT_JOB_ALREADY_RESOLVED"));
        assertThat(fixtures.jobsOfSource(round.batch())).hasSize(2);
    }

    @Test
    void aRetryWithTheSameKeyIsReplayed() throws Exception {
        fail(job);
        String key = UUID.randomUUID().toString();

        String first = retry(job, key, operator()).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        String again = retry(job, key, operator()).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();

        assertThat(again).isEqualTo(first);
        assertThat(fixtures.jobsOfSource(round.batch())).hasSize(2);
    }

    @Test
    void onlyUnknownJobsAreAcknowledged() throws Exception {
        mvc.perform(post(BACKOFFICE + "/" + job + "/acknowledge").header("Idempotency-Key", key()).with(csrf())
                        .with(inStore(shop.store(), Permission.PRODUCTION_OPERATE)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ILLEGAL_PRINT_JOB_STATE"));
    }

    @Test
    void jobCommandsAreScopedToTheirStore() throws Exception {
        fail(job);
        UUID otherStore = fixtures.shop().store();

        retry(job, pos(otherStore, employee, terminal, Permission.PRODUCTION_OPERATE))
                .andExpect(status().isForbidden());
        mvc.perform(post(BACKOFFICE + "/" + job + "/retry").header("Idempotency-Key", key()).with(csrf())
                .with(inStore(otherStore, Permission.PRODUCTION_OPERATE))).andExpect(status().isForbidden());
        retry(job, pos(shop.store(), employee, terminal, Permission.PRODUCTION_VIEW)).andExpect(status().isForbidden());
        // D42 (b): an unknown job is 404 before the store permission
        mvc.perform(post(BACKOFFICE + "/" + UUID.randomUUID() + "/retry").header("Idempotency-Key", key())
                        .with(csrf()).with(inStore(otherStore, Permission.PRODUCTION_OPERATE)))
                .andExpect(status().isNotFound());
        mvc.perform(post(POS + "/" + job + "/retry").with(csrf()).with(operator())).andExpect(status().isBadRequest());
        assertThat(fixtures.jobsOfSource(round.batch())).hasSize(1);
    }

    /** Spec §4: a reprint is identified as a copy, audited; each one is its own job. */
    @Test
    void aReprintIsAnAuditedCopyJobPrintedWithTheCopyMark() throws Exception {
        UUID ticket = (UUID) fixtures.tickets(round.batch()).getFirst().get("id");
        String key = key();

        reprint(ticket, key).andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("COPY"))
                .andExpect(jsonPath("$.ticketId").value(ticket.toString()));
        reprint(ticket, key).andExpect(status().isCreated());
        reprint(ticket, key()).andExpect(status().isCreated());

        assertThat(fixtures.jobsOfSource(round.batch())).extracting(row -> row.get("dedupe_key"))
                .containsExactly("ticket:" + ticket, "copy:" + ticket + ":1", "copy:" + ticket + ":2");
        assertThat(fixtures.audits("PRODUCTION_TICKET_REPRINTED", ticket)).isEqualTo(2);

        try (TcpFakePrinter printer = TcpFakePrinter.accepting()) {
            jdbc.sql("UPDATE production.printer SET port = ?, mode = 'NORMAL', version = version + 1 WHERE id = ?")
                    .param(printer.port()).param(bar).update();
            fixtures.runWorkerUntil(() -> fixtures.jobsOfSource(round.batch()).stream()
                    .allMatch(row -> "SENT".equals(row.get("status"))), "original and copies sent");
            assertThat(printer.awaitPayloads(CodePage.CP860.charset(),
                    text -> text.contains("Pedido #" + round.orderNumber()), 3, Duration.ofSeconds(5)))
                    .filteredOn(text -> text.contains(EscPosRenderer.COPY_MARK)).hasSize(2);
        }
    }

    @Test
    void reprintingNeedsTheOperatePermissionInTheSessionStore() throws Exception {
        UUID ticket = (UUID) fixtures.tickets(round.batch()).getFirst().get("id");

        mvc.perform(post("/api/v1/pos/production-tickets/" + ticket + "/reprints").header("Idempotency-Key", key())
                        .with(csrf()).with(pos(fixtures.shop().store(), employee, terminal,
                                Permission.PRODUCTION_OPERATE))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/pos/production-tickets/" + UUID.randomUUID() + "/reprints")
                        .header("Idempotency-Key", key()).with(csrf()).with(operator()))
                .andExpect(status().isNotFound());
        assertThat(fixtures.jobsOfSource(round.batch())).hasSize(1);
    }

    private ResultActions reprint(UUID ticket, String key) throws Exception {
        return mvc.perform(post("/api/v1/pos/production-tickets/" + ticket + "/reprints")
                .header("Idempotency-Key", key).with(csrf()).with(operator()));
    }

    private ResultActions retry(UUID id, RequestPostProcessor actor) throws Exception {
        return retry(id, key(), actor);
    }

    private ResultActions retry(UUID id, String key, RequestPostProcessor actor) throws Exception {
        return mvc.perform(post(POS + "/" + id + "/retry").header("Idempotency-Key", key).with(csrf()).with(actor));
    }

    private RequestPostProcessor operator() {
        return pos(shop.store(), employee, terminal, Permission.PRODUCTION_OPERATE);
    }

    private void fail(UUID id) {
        jdbc.sql("""
                UPDATE production.print_job SET status = 'FAILED', attempts = 5, started_at = now(),
                    last_error = 'NOT_CONNECTED', version = version + 1 WHERE id = ?""").param(id).update();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
