package com.xavier.backend.orders.controller;

import static com.xavier.backend.orders.OrdersFixtures.POS;
import static com.xavier.backend.orders.OrdersFixtures.id;
import static com.xavier.backend.orders.OrdersFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.dining.DiningApi;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** A1 (orders half): opening a table opens one order and one occupation in one transaction; counter and takeaway. */
@OrdersModuleTest
class OrderOpeningHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    DiningApi dining;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;

    @BeforeEach
    void aShop() throws Exception {
        shop = fixtures.shop();
    }

    @Test
    void openingATableOpensADineInOrderAndOccupiesTheTableInTheSameTransaction() throws Exception {
        JsonNode order = json(fixtures.openTable(shop, shop.table(), employee, terminal, UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("DINE_IN"))
                .andExpect(jsonPath("$.lifecycle").value("OPEN"))
                .andExpect(jsonPath("$.billingLock").value("NONE"))
                .andExpect(jsonPath("$.tableId").value(shop.table().toString()))
                .andExpect(jsonPath("$.tableCode").value("M1"))
                .andExpect(jsonPath("$.guests").value(2))
                .andExpect(jsonPath("$.terminalId").value(terminal.toString()))
                .andExpect(jsonPath("$.openedByEmployeeId").value(employee.toString()))
                .andReturn().getResponse().getContentAsString());

        assertThat(openOrderOf(shop.table())).isEqualTo(id(order));
        assertThat(order.get("orderNumber").asLong()).isPositive();
        assertThat(fixtures.audits("ORDER_OPENED", id(order))).isOne();
    }

    @Test
    void twoTerminalsOpeningOneTableAtOnceGetOneOrderAndOneTableOccupied() throws Exception {
        List<Integer> statuses = OrdersFixtures.race(
                () -> fixtures.openTable(shop, shop.table(), employee, UUID.randomUUID(), UUID.randomUUID().toString())
                        .andReturn().getResponse().getStatus(),
                () -> fixtures.openTable(shop, shop.table(), employee, UUID.randomUUID(), UUID.randomUUID().toString())
                        .andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(ordersAt(shop.table())).isOne();
        fixtures.openTable(shop, shop.table(), employee, terminal, UUID.randomUUID().toString())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_OCCUPIED"));
    }

    @Test
    void aLockedTableIsRefusedAndNoOrderIsLeftBehind() throws Exception {
        fixtures.lockTable(shop, shop.table());

        fixtures.openTable(shop, shop.table(), employee, terminal, UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("TABLE_LOCKED"));

        assertThat(ordersAt(shop.table())).isZero();
    }

    @Test
    void aRetryWithTheSameKeyReplaysTheOpeningWithoutASecondOrder() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = fixtures.openTable(shop, shop.table(), employee, terminal, key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        String replay = fixtures.openTable(shop, shop.table(), employee, terminal, key)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(id(json(replay))).isEqualTo(id(json(first)));
        assertThat(ordersAt(shop.table())).isOne();
    }

    @Test
    void aCounterOrHeldTakeawayOrderHasNoTable() throws Exception {
        openWalkIn("{\"mode\": \"COUNTER\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("COUNTER"))
                .andExpect(jsonPath("$.tableId").isEmpty());
        openWalkIn("{\"mode\": \"TAKEAWAY\", \"guests\": 1}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("TAKEAWAY"));
    }

    @Test
    void aDineInOrderIsOpenedThroughItsTableOnly() throws Exception {
        openWalkIn("{\"mode\": \"DINE_IN\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DINE_IN_REQUIRES_TABLE"));
    }

    @Test
    void aBackOfficeSessionCannotOpenAnOrder() throws Exception {
        mvc.perform(post(POS + "/tables/" + shop.table() + "/open").with(OrdersActors.backOffice(Permission.ORDER_EDIT))
                        .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"guests\": 2}"))
                .andExpect(status().isForbidden());
    }

    org.springframework.test.web.servlet.ResultActions openWalkIn(String body) throws Exception {
        return mvc.perform(post(POS + "/orders")
                .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    long ordersAt(UUID table) {
        return fixtures.count("SELECT count(*) FROM orders.order_header WHERE table_id = ?", table);
    }

    UUID openOrderOf(UUID table) {
        return new TransactionTemplate(transactionManager).execute(status -> dining.tableState(table).openOrderId());
    }
}
