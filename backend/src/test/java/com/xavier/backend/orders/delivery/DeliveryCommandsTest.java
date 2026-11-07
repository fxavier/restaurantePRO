package com.xavier.backend.orders.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.orders.OrderEventProbe;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.delivery.DeliveryFixtures.DeliveryShop;
import com.xavier.backend.orders.model.event.DeliveryDispatched;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * D127 over HTTP: the dispatch's preconditions (no drafts, a confirmed round, a courier of the store), its event and
 * {@code DeliveryDispatched} (one per dispatch, a retry included), the PENDING-only edit, the store scope (D42) and the
 * board and history reads.
 */
@OrdersModuleTest
class DeliveryCommandsTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    OrderEventProbe probe;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    DeliveryFixtures deliveries;
    DeliveryShop d;

    @BeforeEach
    void aDeliveryShop() throws Exception {
        deliveries = new DeliveryFixtures(mvc, jdbc, fixtures, customers);
        d = deliveries.shop();
    }

    @Test
    void aDispatchNeedsNoDraftsAConfirmedRoundAndACourierOfTheStore() throws Exception {
        JsonNode opened = deliveries.opened(d, employee, terminal);
        UUID order = OrdersFixtures.id(opened);
        deliveries.dispatch(d, order, 0, d.courier()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DELIVERY_HAS_DRAFTS"));

        JsonNode fee = opened.get("lines").get(0);
        mvc.perform(delete(DeliveryFixtures.POS + "/orders/" + order + "/lines/" + fee.get("id").asString())
                        .with(OrdersActors.pos(d.shop().store(), employee, terminal, Permission.ORDER_EDIT))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"version\": 0}"))
                .andExpect(status().isNoContent());
        deliveries.dispatch(d, order, 0, d.courier()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOTHING_CONFIRMED"));

        UUID confirmed = deliveries.confirmedDelivery(d, employee, terminal);
        UUID otherStoreEmployee = new IdentityFixtures(mvc).employee(OrdersFixtures.PIN,
                new IdentityFixtures(mvc).store(), IdentityFixtures.WAITER).id();
        deliveries.dispatch(d, confirmed, 0, UUID.randomUUID()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("COURIER_NOT_IN_STORE"));
        deliveries.dispatch(d, confirmed, 0, otherStoreEmployee).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("COURIER_NOT_IN_STORE"));
        assertThat(deliveries.deliveryStatus(confirmed)).isEqualTo("PENDING");

        deliveries.dispatch(d, confirmed, 0, d.courier()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$.courierEmployeeId").value(d.courier().toString()))
                .andExpect(jsonPath("$.history.length()").value(2));
    }

    @Test
    void everyDispatchPublishesOneNoticeWithItsOwnEventAndTerminal() throws Exception {
        UUID order = deliveries.confirmedDelivery(d, employee, terminal);
        deliveries.dispatch(d, order, 0, d.courier()).andExpect(status().isOk());
        deliveries.returned(d, order, 1).andExpect(status().isOk());
        deliveries.dispatch(d, order, 2, d.courier()).andExpect(status().isOk());

        List<DeliveryDispatched> notices = probe.dispatchesOf(order, 2);

        List<UUID> dispatchEvents = jdbc.sql("""
                SELECT id FROM orders.delivery_event WHERE order_id = ? AND to_status = 'OUT_FOR_DELIVERY'
                ORDER BY id""").param(order).query(UUID.class).list();
        assertThat(notices).extracting(DeliveryDispatched::deliveryEventId)
                .containsExactlyInAnyOrderElementsOf(dispatchEvents);
        assertThat(notices).allSatisfy(notice -> assertThat(notice.storeId()).isEqualTo(d.shop().store()));
        assertThat(notices).extracting(DeliveryDispatched::terminalId).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(deliveries.history(order)).containsExactly("->PENDING", "PENDING>OUT_FOR_DELIVERY",
                "OUT_FOR_DELIVERY>RETURNED", "RETURNED>OUT_FOR_DELIVERY");
        assertThat(jdbc.sql("SELECT reason FROM orders.delivery_event WHERE order_id = ? AND to_status = 'RETURNED'")
                .param(order).query(String.class).single()).isEqualTo("nobody home");
        assertThat(fixtures.audits("DELIVERY_RETURNED", order)).isOne();
    }

    @Test
    void wrongStateStaleVersionAndOtherStoreAreRefused() throws Exception {
        UUID order = deliveries.confirmedDelivery(d, employee, terminal);

        deliveries.delivered(d, order, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DELIVERY_STATE"));
        deliveries.dispatch(d, order, 5, d.courier()).andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(DeliveryFixtures.POS + "/deliveries/" + order + "/delivered")
                        .with(OrdersActors.pos(UUID.randomUUID(), employee, terminal, Permission.DELIVERY_OPERATE))
                        .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 0}"))
                .andExpect(status().isForbidden());
        deliveries.delivered(d, UUID.randomUUID(), 0).andExpect(status().isNotFound());
        assertThat(deliveries.version(order)).isZero();
    }

    @Test
    void theAddressZoneAndPromiseChangeWhilePendingAndAreFrozenFromTheDispatch() throws Exception {
        UUID order = OrdersFixtures.id(deliveries.opened(d, employee, terminal));
        UUID otherFee = deliveries.feeItem(d.shop(), "5", "4.50");
        long catalogVersion = fixtures.republish(d.shop().store());
        UUID otherZone = deliveries.zone(d.shop(), "O" + CustomersFixtures.token().substring(0, 6), otherFee);

        update(order, """
                {"version": 0, "address": {"line1": "Av. Nova 9", "city": "Matola"}, "zoneId": "%s",
                 "promisedAt": "2027-03-01T20:00:00Z"}""".formatted(otherZone)).andExpect(status().isOk())
                .andExpect(jsonPath("$.address.line1").value("Av. Nova 9"))
                .andExpect(jsonPath("$.zoneId").value(otherZone.toString()));

        JsonNode view = fixtures.order(d.shop(), order);
        assertThat(DeliveryFixtures.list(view.get("lines"))).singleElement()
                .satisfies(line -> assertThat(line.get("itemId").asString()).isEqualTo(otherFee.toString()));
        UUID item = OrdersFixtures.id(fixtures.draft(d.shop(), order, "1", employee, terminal));
        fixtures.confirm(d.shop(), order, 0, catalogVersion, List.of(UUID.fromString(
                        view.get("lines").get(0).get("id").asString()), item), employee, terminal,
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        deliveries.dispatch(d, order, 1, d.courier()).andExpect(status().isOk());

        update(order, "{\"version\": 2, \"promisedAt\": \"2027-03-01T21:00:00Z\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("ADDRESS_FROZEN"));
    }

    @Test
    void theBoardShowsTheActiveDeliveriesAndTheHistoryFiltersByCourier() throws Exception {
        UUID out = deliveries.confirmedDelivery(d, employee, terminal);
        deliveries.dispatch(d, out, 0, d.courier()).andExpect(status().isOk());
        UUID cancelled = OrdersFixtures.id(deliveries.opened(d, employee, terminal));
        deliveries.cancel(d, cancelled, 0).andExpect(status().isOk());

        JsonNode board = OrdersFixtures.json(mvc.perform(get(DeliveryFixtures.POS + "/deliveries")
                        .with(OrdersActors.pos(d.shop().store(), employee, terminal, Permission.ORDER_VIEW)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(DeliveryFixtures.list(board)).extracting(node -> node.get("orderId").asString())
                .contains(out.toString()).doesNotContain(cancelled.toString());
        mvc.perform(get(DeliveryFixtures.POS + "/deliveries").param("status", "CANCELLED")
                        .with(OrdersActors.pos(d.shop().store(), employee, terminal, Permission.ORDER_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].history.length()").value(2));

        String day = jdbc.sql("SELECT business_day::text FROM orders.order_header WHERE id = ?").param(out)
                .query(String.class).single();
        mvc.perform(get("/api/v1/backoffice/deliveries").param("storeId", d.shop().store().toString())
                        .param("from", day).param("to", day).param("courierId", d.courier().toString())
                        .with(OrdersActors.backOffice(Permission.ORDER_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(out.toString()));
        mvc.perform(get("/api/v1/backoffice/deliveries").param("storeId", d.shop().store().toString())
                        .param("from", day).param("to", day).with(OrdersActors.backOffice(Permission.REPORT_VIEW)))
                .andExpect(status().isForbidden());
    }

    org.springframework.test.web.servlet.ResultActions update(UUID order, String body) throws Exception {
        return mvc.perform(put(DeliveryFixtures.POS + "/deliveries/" + order)
                .with(OrdersActors.pos(d.shop().store(), employee, terminal, Permission.DELIVERY_OPERATE))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
