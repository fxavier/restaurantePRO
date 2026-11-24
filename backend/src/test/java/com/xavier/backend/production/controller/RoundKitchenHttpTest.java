package com.xavier.backend.production.controller;

import static com.xavier.backend.fiscal.FiscalActors.pos;
import static com.xavier.backend.production.ProductionFixtures.await;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The kitchen state of an order's rounds for the POS order panel (ADR 0044, light reference 2026-11-23): one read per
 * order, each round with the state of its least advanced station, so a round shows "pronta" only when every station
 * has it READY or further. Orders cannot read production (production depends on orders, D35), so the read is here.
 */
@ProductionModuleTest
class RoundKitchenHttpTest {

    static final String TICKETS = "/api/v1/pos/production-tickets";
    static final String ROUNDS = TICKETS + "/rounds";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    OrdersFixtures orders;

    final UUID waiter = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;

    @BeforeEach
    void shop() throws Exception {
        shop = fixtures.shop();
    }

    @Test
    void aSentRoundFollowsItsKitchenState() throws Exception {
        Round round = fixtures.confirmedRound(shop, waiter, terminal, "1");
        UUID ticket = queued(round);

        rounds(round.order()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].batchId").value(round.batch().toString()))
                .andExpect(jsonPath("$[0].status").value("QUEUED"));
        move(ticket, "start", 0);
        rounds(round.order()).andExpect(jsonPath("$[0].status").value("IN_PREPARATION"));
        move(ticket, "ready", 1);
        rounds(round.order()).andExpect(jsonPath("$[0].status").value("READY"));
    }

    @Test
    void aRoundOnTwoStationsIsInPreparationOnceOneStartsAndReadyOnlyWhenBothAre() throws Exception {
        Round round = fixtures.confirmedRound(shop, waiter, terminal, "1");
        UUID bar = queued(round);
        UUID kitchen = stationTicket(round, "KITCHEN");

        rounds(round.order()).andExpect(jsonPath("$[0].status").value("QUEUED"));
        move(bar, "start", 0);
        rounds(round.order()).andExpect(jsonPath("$[0].status").value("IN_PREPARATION"));
        move(bar, "ready", 1);
        rounds(round.order()).andExpect(jsonPath("$[0].status").value("IN_PREPARATION"));
        move(kitchen, "start", 0);
        move(kitchen, "ready", 1);
        rounds(round.order()).andExpect(jsonPath("$[0].status").value("READY"));
        move(kitchen, "bump", 2);
        move(bar, "bump", 2);
        rounds(round.order()).andExpect(jsonPath("$[0].status").value("READY"));
    }

    @Test
    void anOrderWithoutRoundsHasNone() throws Exception {
        UUID order = OrdersFixtures.id(orders.openedOrder(shop, waiter, terminal));

        rounds(order).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anotherStoresOrderIsForbiddenAndAnUnknownOneNotFound() throws Exception {
        Round round = fixtures.confirmedRound(shop, waiter, terminal, "1");
        Shop other = fixtures.shop();

        mvc.perform(get(ROUNDS).param("orderId", round.order().toString())
                        .with(pos(other.store(), waiter, terminal, Permission.ORDER_VIEW)))
                .andExpect(status().isForbidden());
        rounds(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void readingNeedsOrderView() throws Exception {
        Round round = fixtures.confirmedRound(shop, waiter, terminal, "1");

        mvc.perform(get(ROUNDS).param("orderId", round.order().toString())
                        .with(pos(shop.store(), waiter, terminal, Permission.DINING_VIEW)))
                .andExpect(status().isForbidden());
    }

    private ResultActions rounds(UUID order) throws Exception {
        return mvc.perform(get(ROUNDS).param("orderId", order.toString())
                .with(pos(shop.store(), waiter, terminal, Permission.ORDER_VIEW)));
    }

    /** The round's ticket once production has received it and queued it on its station (the shop's item: BAR). */
    private UUID queued(Round round) throws InterruptedException {
        await(() -> fixtures.tickets(round.batch()).size() == 1, "round ticket");
        UUID ticket = (UUID) fixtures.tickets(round.batch()).getFirst().get("id");
        await(() -> jdbc.sql("SELECT count(*) FROM production.kitchen_progress WHERE ticket_id = ?").param(ticket)
                .query(Long.class).single() == 1, "round queued");
        return ticket;
    }

    /** The same round's ticket on another station, written as production writes it. */
    private UUID stationTicket(Round round, String destination) {
        UUID ticket = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO production.production_ticket (id, store_id, source_type, source_id, destination, kind,
                    order_id, terminal_id, routing, suppressed, content, created_at)
                VALUES (?, ?, 'BATCH', ?, ?, 'ORIGINAL', ?, ?, 'UNROUTED', false, 'grill', now())""")
                .param(ticket).param(shop.store()).param(round.batch()).param(destination).param(round.order())
                .param(terminal).update();
        jdbc.sql("""
                INSERT INTO production.kitchen_progress (ticket_id, store_id, destination, status, version, queued_at)
                VALUES (?, ?, ?, 'QUEUED', 0, now())""").param(ticket).param(shop.store()).param(destination)
                .update();
        return ticket;
    }

    private void move(UUID ticket, String command, long version) throws Exception {
        mvc.perform(post(TICKETS + "/" + ticket + "/" + command).with(csrf())
                        .with(pos(shop.store(), UUID.randomUUID(), terminal, Permission.PRODUCTION_OPERATE))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": " + version + "}"))
                .andExpect(status().isOk());
    }
}
