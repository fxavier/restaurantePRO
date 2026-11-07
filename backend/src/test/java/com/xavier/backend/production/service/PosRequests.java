package com.xavier.backend.production.service;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.delivery.DeliveryFixtures.DeliveryShop;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** POS requests the R4 production tests send from a terminal they choose, so its printer is known beforehand. */
final class PosRequests {

    private PosRequests() {
    }

    /** {@code POST /pos/orders {mode}} for a walk-in order; its JSON view. */
    static JsonNode openWalkIn(MockMvc mvc, UUID store, String mode, UUID employee, UUID terminal) throws Exception {
        return OrdersFixtures.json(mvc.perform(post(OrdersFixtures.POS + "/orders")
                        .with(OrdersActors.pos(store, employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"%s\"}".formatted(mode)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** A delivery command ({@code dispatch}, {@code returned}…) from {@code terminal}'s POS session. */
    static ResultActions delivery(MockMvc mvc, DeliveryShop d, UUID order, UUID terminal, String command, String body)
            throws Exception {
        return mvc.perform(post(OrdersFixtures.POS + "/deliveries/" + order + "/" + command)
                .with(OrdersActors.pos(d.shop().store(), UUID.randomUUID(), terminal, Permission.DELIVERY_OPERATE))
                .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /**
     * {@code POST /pos/orders/{order}/voids} of {@code quantity} of {@code line}, approved by {@code approver} (PIN
     * {@link OrdersFixtures#PIN}); the void's id.
     */
    static UUID voided(MockMvc mvc, JdbcClient jdbc, UUID store, UUID order, UUID line, String quantity,
                       IdentityFixtures.Employee approver) throws Exception {
        long version = jdbc.sql("SELECT version FROM orders.order_header WHERE id = ?").param(order)
                .query(Long.class).single();
        mvc.perform(post(OrdersFixtures.POS + "/orders/" + order + "/voids")
                        .with(OrdersActors.pos(store, UUID.randomUUID(), UUID.randomUUID(), Permission.ORDER_VOID))
                        .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Approver-Code", approver.code()).header("X-Approver-Pin", OrdersFixtures.PIN)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderVersion": %d, "lineId": "%s", "quantity": %s, "reason": "engano",
                                 "wasted": false}""".formatted(version, line, quantity)))
                .andExpect(status().isCreated());
        return jdbc.sql("SELECT id FROM orders.order_line_void WHERE line_id = ? ORDER BY id DESC LIMIT 1")
                .param(line).query(UUID.class).single();
    }

    static long orderNumber(JdbcClient jdbc, UUID order) {
        return jdbc.sql("SELECT order_number FROM orders.order_header WHERE id = ?").param(order).query(Long.class)
                .single();
    }

    static UUID lastBatch(JdbcClient jdbc, UUID order) {
        return jdbc.sql("SELECT id FROM orders.order_batch WHERE order_id = ? ORDER BY batch_no DESC LIMIT 1")
                .param(order).query(UUID.class).single();
    }
}
