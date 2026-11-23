package com.xavier.backend.orders.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.TestAuthentications;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersModuleTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/** The BackOffice order search and detail: a store's orders, read with ORDER_VIEW in that store (D42). */
@OrdersModuleTest
class BackOfficeOrderHttpTest {

    static final String ORDERS = "/api/v1/backoffice/orders";

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    OrdersFixtures.Shop shop;
    UUID employee = UUID.randomUUID();
    UUID terminal = UUID.randomUUID();
    JsonNode dineIn;
    UUID takeaway;
    UUID cancelled;

    @BeforeEach
    void setUp() throws Exception {
        shop = fixtures.shop();
        dineIn = fixtures.openedOrder(shop, employee, terminal);
        fixtures.confirmedLine(shop, OrdersFixtures.id(dineIn), "2", dineIn.get("version").asLong(), employee,
                terminal);
        takeaway = OrdersFixtures.id(openTakeaway());
        JsonNode toCancel = openTakeaway();
        cancelled = OrdersFixtures.id(toCancel);
        mvc.perform(post("/api/v1/pos/orders/" + cancelled + "/cancel")
                        .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_CANCEL)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"orderVersion": %d, "reason": "customer left"}"""
                                .formatted(toCancel.get("version").asLong())))
                .andExpect(status().isOk());
    }

    private JsonNode openTakeaway() throws Exception {
        return OrdersFixtures.json(mvc.perform(post("/api/v1/pos/orders")
                        .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"TAKEAWAY\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void aStoresOrdersAreSearchedWithTheirTableGuestsTimesAndDue() throws Exception {
        mvc.perform(get(ORDERS).param("storeId", shop.store().toString()).param("mode", "DINE_IN")
                        .with(viewer(shop.store())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(dineIn.get("id").asString()))
                .andExpect(jsonPath("$.content[0].orderNumber").value(dineIn.get("orderNumber").asLong()))
                .andExpect(jsonPath("$.content[0].mode").value("DINE_IN"))
                .andExpect(jsonPath("$.content[0].tableCode").value("M1"))
                .andExpect(jsonPath("$.content[0].guests").value(2))
                .andExpect(jsonPath("$.content[0].lifecycle").value("OPEN"))
                .andExpect(jsonPath("$.content[0].openedAt").isString())
                .andExpect(jsonPath("$.content[0].closedAt").isEmpty())
                .andExpect(jsonPath("$.content[0].due.amount").value("25.00"));
    }

    @Test
    void theSearchFiltersByTextStatusAndBusinessDayAndPages() throws Exception {
        String store = shop.store().toString();
        String day = dineIn.get("businessDay").asString();

        mvc.perform(get(ORDERS).param("storeId", store).param("q", "m1").with(viewer(shop.store())))
                .andExpect(jsonPath("$.content[*].id", contains(dineIn.get("id").asString())));
        mvc.perform(get(ORDERS).param("storeId", store).param("q", dineIn.get("orderNumber").asString())
                        .with(viewer(shop.store())))
                .andExpect(jsonPath("$.content[*].id", contains(dineIn.get("id").asString())));
        mvc.perform(get(ORDERS).param("storeId", store).param("status", "OPEN").param("from", day)
                        .param("to", day).with(viewer(shop.store())))
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(dineIn.get("id").asString(),
                        takeaway.toString())));
        mvc.perform(get(ORDERS).param("storeId", store).param("status", "CLOSED").with(viewer(shop.store())))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(ORDERS).param("storeId", store).param("status", "CANCELLED").with(viewer(shop.store())))
                .andExpect(jsonPath("$.content[*].id", contains(cancelled.toString())))
                .andExpect(jsonPath("$.content[0].lifecycle").value("CANCELLED"))
                .andExpect(jsonPath("$.content[0].closedAt").isString());
        mvc.perform(get(ORDERS).param("storeId", store).param("to", "2000-01-01").with(viewer(shop.store())))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(ORDERS).param("storeId", store).param("size", "1").with(viewer(shop.store())))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void aSearchTextLongerThanATableCodeIsRejected() throws Exception {
        mvc.perform(get(ORDERS).param("storeId", shop.store().toString()).param("q", "x".repeat(21))
                        .with(viewer(shop.store())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("q"));
        mvc.perform(get(ORDERS).param("storeId", shop.store().toString()).param("q", "x".repeat(20))
                        .with(viewer(shop.store())))
                .andExpect(status().isOk());
    }

    @Test
    void anOrderIsReadWithItsLinesAndDue() throws Exception {
        mvc.perform(get(ORDERS + "/" + dineIn.get("id").asString()).with(viewer(shop.store())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableCode").value("M1"))
                .andExpect(jsonPath("$.closedAt").isEmpty())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.due.amount").value("25.00"));
    }

    @Test
    void aCancelledOrderIsReadWithWhenItClosed() throws Exception {
        mvc.perform(get(ORDERS + "/" + cancelled).with(viewer(shop.store())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("CANCELLED"))
                .andExpect(jsonPath("$.closedAt").isString());
    }

    @Test
    void readingNeedsOrderViewInTheOrdersStore() throws Exception {
        UUID otherStore = UUID.randomUUID();

        mvc.perform(get(ORDERS).param("storeId", shop.store().toString()).with(viewer(otherStore)))
                .andExpect(status().isForbidden());
        mvc.perform(get(ORDERS + "/" + takeaway).with(viewer(otherStore)))
                .andExpect(status().isForbidden());
        mvc.perform(get(ORDERS + "/" + UUID.randomUUID()).with(viewer(shop.store())))
                .andExpect(status().isNotFound());
    }

    private static RequestPostProcessor viewer(UUID store) {
        return authentication(TestAuthentications.of(Actor.account(UUID.randomUUID(),
                Set.of(Permission.ORDER_VIEW.authority(store)))));
    }
}
