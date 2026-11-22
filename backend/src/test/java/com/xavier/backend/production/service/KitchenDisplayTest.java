package com.xavier.backend.production.service;

import static com.xavier.backend.fiscal.FiscalActors.pos;
import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionFixtures.Round;
import com.xavier.backend.production.ProductionModuleTest;
import com.xavier.backend.shared.notify.ChangeNotifier;
import com.xavier.backend.shared.notify.ChangeTopic;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The kitchen display (ADR 0044) on the POS: every round is queued on its production destination (a station) when
 * production receives it, printer or not; a KDS lists its station's rounds by status and moves them start → ready →
 * bump, with recall one step back. Commands are idempotent, carry the version the screen shows, run in the
 * session's store with PRODUCTION_OPERATE, are audited and announced on the change stream.
 */
@ProductionModuleTest
class KitchenDisplayTest {

    static final String TICKETS = "/api/v1/pos/production-tickets";
    static final String KDS = TICKETS + "/kds";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    ChangeNotifier notifier;

    final UUID cook = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;

    @BeforeEach
    void shop() throws Exception {
        shop = fixtures.shop();
    }

    @Test
    void aReceivedRoundIsQueuedOnItsStationAndAnnounced() throws Exception {
        UUID ticket = queuedRound();

        mvc.perform(get(KDS).param("destination", ProductionFixtures.DESTINATION).param("status", "QUEUED")
                        .with(viewer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(ticket.toString()))
                .andExpect(jsonPath("$.content[0].sourceType").value("BATCH"))
                .andExpect(jsonPath("$.content[0].destination").value(ProductionFixtures.DESTINATION))
                .andExpect(jsonPath("$.content[0].lines").isNotEmpty())
                .andExpect(jsonPath("$.content[0].kitchen.status").value("QUEUED"))
                .andExpect(jsonPath("$.content[0].kitchen.version").value(0))
                .andExpect(jsonPath("$.content[0].kitchen.queuedAt").isNotEmpty())
                .andExpect(jsonPath("$.content[0].kitchen.started").doesNotExist());
        verify(notifier, timeout(5000)).notifyAfterCommit(eq(shop.store()), eq(ChangeTopic.PRODUCTION_TICKETS),
                eq(ticket));
    }

    @Test
    void startReadyBumpAndRecallMoveTheRoundWithTheOperatorAndTime() throws Exception {
        UUID ticket = queuedRound();

        command(ticket, "start", 0, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ticket.toString()))
                .andExpect(jsonPath("$.kitchen.status").value("IN_PREPARATION"))
                .andExpect(jsonPath("$.kitchen.version").value(1))
                .andExpect(jsonPath("$.kitchen.started.by").value(cook.toString()))
                .andExpect(jsonPath("$.kitchen.started.at").isNotEmpty());
        command(ticket, "ready", 1, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.kitchen.status").value("READY"))
                .andExpect(jsonPath("$.kitchen.ready.by").value(cook.toString()));
        command(ticket, "bump", 2, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.kitchen.status").value("BUMPED"))
                .andExpect(jsonPath("$.kitchen.bumped.at").isNotEmpty());
        command(ticket, "recall", 3, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.kitchen.status").value("READY"))
                .andExpect(jsonPath("$.kitchen.bumped").doesNotExist())
                .andExpect(jsonPath("$.kitchen.recalled.by").value(cook.toString()));
        command(ticket, "recall", 4, key()).andExpect(status().isOk())
                .andExpect(jsonPath("$.kitchen.status").value("IN_PREPARATION"))
                .andExpect(jsonPath("$.kitchen.ready").doesNotExist());

        assertThat(fixtures.audits("PRODUCTION_TICKET_STARTED", ticket)).isOne();
        assertThat(fixtures.audits("PRODUCTION_TICKET_READY", ticket)).isOne();
        assertThat(fixtures.audits("PRODUCTION_TICKET_BUMPED", ticket)).isOne();
        assertThat(fixtures.audits("PRODUCTION_TICKET_RECALLED", ticket)).isEqualTo(2);
        verify(notifier, timeout(5000).times(6)).notifyAfterCommit(eq(shop.store()),
                eq(ChangeTopic.PRODUCTION_TICKETS), eq(ticket));
    }

    @Test
    void anIllegalTransitionIsRefusedAndChangesNothing() throws Exception {
        UUID ticket = queuedRound();

        command(ticket, "ready", 0, key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_KITCHEN_STATE"));
        command(ticket, "bump", 0, key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_KITCHEN_STATE"));
        command(ticket, "recall", 0, key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_KITCHEN_STATE"));

        assertThat(progress(ticket)).isEqualTo("QUEUED");
        assertThat(fixtures.audits("PRODUCTION_TICKET_READY", ticket)).isZero();
    }

    @Test
    void aStaleVersionIsAConflict() throws Exception {
        UUID ticket = queuedRound();
        command(ticket, "start", 0, key()).andExpect(status().isOk());

        command(ticket, "ready", 0, key()).andExpect(status().isConflict());

        assertThat(progress(ticket)).isEqualTo("IN_PREPARATION");
    }

    @Test
    void aReplayedCommandReturnsTheSameResponseAndMovesTheRoundOnce() throws Exception {
        UUID ticket = queuedRound();
        String key = key();

        String first = command(ticket, "start", 0, key).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String replay = command(ticket, "start", 0, key).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(fixtures.audits("PRODUCTION_TICKET_STARTED", ticket)).isOne();
        command(ticket, "start", 0, key()).andExpect(status().isConflict());
        command(ticket, "start", 0, null).andExpect(status().isBadRequest());
    }

    @Test
    void twoScreensStartingTheSameRoundAtOnceMoveItOnce() throws Exception {
        UUID ticket = queuedRound();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> statuses = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                statuses.add(pool.submit(() -> {
                    go.await();
                    return command(ticket, "start", 0, key()).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> result : statuses) {
                results.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(fixtures.audits("PRODUCTION_TICKET_STARTED", ticket)).isOne();
    }

    @Test
    void theKdsSeesOnlyTheSessionsStoreAndOperatingNeedsThePermission() throws Exception {
        UUID ticket = queuedRound();
        UUID otherStore = fixtures.shop().store();

        mvc.perform(get(KDS).with(pos(otherStore, cook, terminal, Permission.PRODUCTION_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[?(@.id == '%s')]", ticket).isEmpty());
        command(ticket, "start", 0, key(), pos(otherStore, cook, terminal, Permission.PRODUCTION_OPERATE))
                .andExpect(status().isForbidden());
        command(ticket, "start", 0, key(), viewer()).andExpect(status().isForbidden());
        mvc.perform(get(KDS).with(pos(shop.store(), cook, terminal, Permission.PRODUCTION_OPERATE)))
                .andExpect(status().isForbidden());
        command(UUID.randomUUID(), "start", 0, key()).andExpect(status().isNotFound());

        assertThat(progress(ticket)).isEqualTo("QUEUED");
    }

    @Test
    void onlyARoundIsPreparedOnTheKitchenDisplay() throws Exception {
        UUID subtotal = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO production.production_ticket (id, store_id, source_type, source_id, destination, kind,
                    terminal_id, routing, suppressed, content, created_at)
                VALUES (?, ?, 'SUBTOTAL', ?, 'SUBTOTAL', 'ORIGINAL', ?, 'UNROUTED', false, 'subtotal', now())""")
                .param(subtotal).param(shop.store()).param(UUID.randomUUID()).param(terminal).update();

        command(subtotal, "start", 0, key()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOT_KITCHEN_WORK"));
    }

    @Test
    void theStationAndStatusFiltersNarrowTheList() throws Exception {
        UUID bar = queuedRound();
        UUID grill = roundOn("GRILL");
        command(grill, "start", 0, key()).andExpect(status().isOk());

        assertThat(listed(get(KDS).param("destination", "GRILL"))).containsExactly(grill.toString());
        assertThat(listed(get(KDS).param("destination", ProductionFixtures.DESTINATION)))
                .containsExactly(bar.toString());
        assertThat(listed(get(KDS).param("status", "IN_PREPARATION"))).containsExactly(grill.toString());
        assertThat(listed(get(KDS).param("status", "QUEUED").param("status", "IN_PREPARATION")))
                .containsExactly(bar.toString(), grill.toString());
        assertThat(listed(get(KDS).param("destination", "GRILL").param("status", "QUEUED"))).isEmpty();
    }

    @Test
    void theTableRefusesAProgressInconsistentWithItsStatus() throws Exception {
        UUID ticket = queuedRound();

        assertThatThrownBy(() -> jdbc.sql("UPDATE production.kitchen_progress SET status = 'READY' WHERE ticket_id = ?")
                .param(ticket).update()).hasMessageContaining("kitchen_progress_");
    }

    /** The shop's item, confirmed in a round (BAR has no route here, so the ticket is UNROUTED, still queued). */
    private UUID queuedRound() throws Exception {
        Round round = fixtures.confirmedRound(shop, cook, terminal, "1");
        await(() -> fixtures.tickets(round.batch()).size() == 1, "round ticket");
        UUID ticket = (UUID) fixtures.tickets(round.batch()).getFirst().get("id");
        await(() -> progressRows(ticket) == 1, "round queued on the kitchen display");
        return ticket;
    }

    /** A round ticket of another station, written as production writes it (the shop's item goes to BAR only). */
    private UUID roundOn(String destination) {
        UUID ticket = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO production.production_ticket (id, store_id, source_type, source_id, destination, kind,
                    terminal_id, routing, suppressed, content, created_at)
                VALUES (?, ?, 'BATCH', ?, ?, 'ORIGINAL', ?, 'UNROUTED', false, 'grill', now())""")
                .param(ticket).param(shop.store()).param(UUID.randomUUID()).param(destination).param(terminal)
                .update();
        jdbc.sql("""
                INSERT INTO production.kitchen_progress (ticket_id, store_id, destination, status, version, queued_at)
                VALUES (?, ?, ?, 'QUEUED', 0, now())""").param(ticket).param(shop.store()).param(destination)
                .update();
        return ticket;
    }

    private List<String> listed(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder list)
            throws Exception {
        String body = mvc.perform(list.with(viewer())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        List<String> ids = new ArrayList<>();
        OrdersFixtures.json(body).get("content").forEach(ticket -> ids.add(ticket.get("id").asString()));
        return ids;
    }

    private ResultActions command(UUID ticket, String command, long version, String key) throws Exception {
        return command(ticket, command, version, key, pos(shop.store(), cook, terminal,
                Permission.PRODUCTION_OPERATE));
    }

    private ResultActions command(UUID ticket, String command, long version, String key, RequestPostProcessor actor)
            throws Exception {
        var request = post(TICKETS + "/" + ticket + "/" + command).with(csrf()).with(actor)
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\": " + version + "}");
        return mvc.perform(key == null ? request : request.header("Idempotency-Key", key));
    }

    private RequestPostProcessor viewer() {
        return pos(shop.store(), cook, terminal, Permission.PRODUCTION_VIEW);
    }

    private String progress(UUID ticket) {
        return jdbc.sql("SELECT status FROM production.kitchen_progress WHERE ticket_id = ?").param(ticket)
                .query(String.class).single();
    }

    private long progressRows(UUID ticket) {
        return jdbc.sql("SELECT count(*) FROM production.kitchen_progress WHERE ticket_id = ?").param(ticket)
                .query(Long.class).single();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
