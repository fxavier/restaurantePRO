package com.xavier.backend.orders.controller;

import static com.xavier.backend.orders.OrdersFixtures.POS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrderEventProbe;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.model.event.SubtotalLine;
import com.xavier.backend.orders.model.event.SubtotalRequested;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Cancel (r2-decisions §4 orders: net billable quantity 0 and no billing lock; frees the table) and the subtotal
 * printout (§8.1: a non-fiscal bill printed for the table, not a document; §8.4 marks the table).
 */
@OrdersModuleTest
class CancelAndSubtotalHttpTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrderEventProbe events;

    @Autowired
    OrdersApi ordersApi;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;

    @BeforeEach
    void anOpenTable() throws Exception {
        shop = fixtures.shop();
        order = fixtures.openedAt(shop, shop.table(), employee, terminal);
    }

    @Test
    void anOrderThatBillsNothingIsCancelledAndFreesItsTable() throws Exception {
        fixtures.draft(shop, order, "1", employee, terminal);

        cancel(0, "customer left").andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("CANCELLED"));

        assertThat(fixtures.count("""
                SELECT count(*) FROM dining.table_occupation WHERE order_id = ? AND close_reason = 'ORDER_CANCELLED'""",
                order)).isOne();
        assertThat(fixtures.count("""
                SELECT count(*) FROM audit.audit_event WHERE action = 'ORDER_CANCELLED' AND entity_id = ?
                    AND reason = 'customer left'""", order.toString())).isOne();
    }

    @Test
    void anOrderWhoseLinesWereAllTransferredAwayCanBeCancelled() throws Exception {
        UUID line = fixtures.confirmedLine(shop, order, "2", 0, employee, terminal);
        UUID other = fixtures.openedAt(shop, fixtures.newTable(shop, "M2"), employee, terminal);
        fixtures.transfer(shop, order, 1, other, 0, Map.of(line, "2"), false, employee, terminal,
                UUID.randomUUID().toString()).andExpect(status().isCreated());

        cancel(2, "moved to M2").andExpect(status().isOk());
    }

    @Test
    void anOrderThatStillBillsSomethingIsNotCancelled() throws Exception {
        fixtures.confirmedLine(shop, order, "1", 0, employee, terminal);

        cancel(1, "oops").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_EMPTY"));
    }

    @Test
    void anOrderWithAPaymentUnderWayIsNotCancelled() throws Exception {
        fixtures.inPos(shop, () -> ordersApi.lockForPayment(order));

        cancel(1, "oops").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ORDER_IN_SETTLEMENT"));
    }

    @Test
    void cancelNeedsTheCurrentVersionAndAReason() throws Exception {
        cancel(3, "late").andExpect(status().isConflict());
        cancel(0, " ").andExpect(status().isBadRequest());
    }

    @Test
    void aSubtotalPublishesTheBillForProductionAndMarksTheTableWithoutChangingTheOrder() throws Exception {
        fixtures.confirmedLine(shop, order, "2", 0, employee, terminal);
        fixtures.draft(shop, order, "5", employee, terminal);

        subtotal(UUID.randomUUID().toString()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.due.amount").value(25.00));

        List<SubtotalRequested> printed = events.subtotalsOf(order, 1);
        assertThat(printed).singleElement().satisfies(event -> {
            assertThat(event.storeId()).isEqualTo(shop.store());
            assertThat(event.terminalId()).isEqualTo(terminal);
            assertThat(event.tableCode()).isEqualTo("M1");
            assertThat(event.lines()).containsExactly(new SubtotalLine("Coca-Cola 33cl", new BigDecimal("2.000"),
                    Money.of("12.50", MZN), Money.of("25.00", MZN)));
            assertThat(event.due()).isEqualTo(Money.of("25.00", MZN));
        });
        assertThat(fixtures.count("""
                SELECT count(*) FROM dining.table_occupation WHERE order_id = ? AND closed_at IS NULL
                    AND subtotal_printed_at IS NOT NULL""", order)).isOne();
        assertThat(fixtures.order(shop, order).get("version").asLong()).isEqualTo(1);
    }

    @Test
    void theSameKeyPrintsTheSubtotalOnce() throws Exception {
        fixtures.confirmedLine(shop, order, "1", 0, employee, terminal);
        String key = UUID.randomUUID().toString();

        String first = subtotal(key).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String replay = subtotal(key).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
        assertThat(fixtures.audits("ORDER_SUBTOTAL_REQUESTED", order)).isOne();
    }

    @Test
    void aWalkInOrderGetsASubtotalWithoutATable() throws Exception {
        String counter = mvc.perform(post(POS + "/orders")
                        .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"TAKEAWAY\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID takeaway = OrdersFixtures.id(OrdersFixtures.json(counter));

        mvc.perform(post(POS + "/orders/" + takeaway + "/subtotal/print")
                        .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.due.amount").value(0));

        assertThat(events.subtotalsOf(takeaway, 1)).singleElement()
                .satisfies(event -> assertThat(event.tableCode()).isNull());
    }

    @Test
    void aCancelledOrderGetsNoSubtotal() throws Exception {
        cancel(0, "customer left").andExpect(status().isOk());

        subtotal(UUID.randomUUID().toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_OPEN"));
    }

    ResultActions cancel(long version, String reason) throws Exception {
        return mvc.perform(post(POS + "/orders/" + order + "/cancel")
                .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_CANCEL)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"orderVersion": %d, "reason": "%s"}""".formatted(version, reason)));
    }

    ResultActions subtotal(String key) throws Exception {
        return mvc.perform(post(POS + "/orders/" + order + "/subtotal/print")
                .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                .header("Idempotency-Key", key));
    }
}
