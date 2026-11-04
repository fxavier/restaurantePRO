package com.xavier.backend.orders.controller;

import static com.xavier.backend.orders.OrdersFixtures.POS;
import static com.xavier.backend.orders.OrdersFixtures.id;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** Server-side DRAFT lines: priced as a preview, editable under their own version, never bumping the order. */
@OrdersModuleTest
class DraftLineHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;

    @BeforeEach
    void anOpenTable() throws Exception {
        shop = fixtures.shop();
        order = id(fixtures.openedOrder(shop, employee, terminal));
    }

    @Test
    void aNewLineIsADraftWithAPreviewPriceAndTheOrderVersionDoesNotMove() throws Exception {
        fixtures.addLine(shop, order, """
                        {"itemId": "%s", "quantity": 2, "notes": "no ice"}""".formatted(shop.item()), employee,
                        terminal, UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.preview").value(true))
                .andExpect(jsonPath("$.unitPrice.amount").value(12.50))
                .andExpect(jsonPath("$.gross.amount").value(25.00))
                .andExpect(jsonPath("$.notes").value("no ice"))
                .andExpect(jsonPath("$.batchId").isEmpty());

        getOrder().andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.catalogVersion").value(shop.catalogVersion()))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.due.amount").value(0.00));
    }

    @Test
    void anItemThatIsNotInThePublishedCatalogIsRefused() throws Exception {
        fixtures.addLine(shop, order, """
                        {"itemId": "%s", "quantity": 1}""".formatted(UUID.randomUUID()), employee, terminal,
                        UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_IN_CATALOG"));
    }

    @Test
    void aLockedTableTakesNoNewLines() throws Exception {
        fixtures.lockTable(shop, shop.table());

        fixtures.addLine(shop, order, """
                        {"itemId": "%s", "quantity": 1}""".formatted(shop.item()), employee, terminal,
                        UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("TABLE_LOCKED"));
    }

    @Test
    void aDraftIsEditedUnderItsVersionAndAStaleEditIsAConflict() throws Exception {
        JsonNode line = fixtures.draft(shop, order, "1", employee, terminal);

        editLine(id(line), "{\"version\": 0, \"quantity\": 0.457}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(0.457))
                .andExpect(jsonPath("$.version").value(1));
        editLine(id(line), "{\"version\": 0, \"quantity\": 3}").andExpect(status().isConflict());
        editLine(id(line), "{\"version\": 1, \"quantity\": 0}").andExpect(status().isBadRequest());
    }

    @Test
    void aDraftIsDeletedUnderItsVersion() throws Exception {
        JsonNode line = fixtures.draft(shop, order, "1", employee, terminal);

        deleteLine(id(line), "{\"version\": 5}").andExpect(status().isConflict());
        deleteLine(id(line), "{\"version\": 0}").andExpect(status().isNoContent());

        getOrder().andExpect(jsonPath("$.lines.length()").value(0));
        deleteLine(id(line), "{\"version\": 0}").andExpect(status().isNotFound());
    }

    @Test
    void theOpenOrdersOfTheSessionStoreAreListed() throws Exception {
        mvc.perform(get(POS + "/orders").param("lifecycle", "OPEN")
                        .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(Matchers.contains(order.toString())))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void anOrderOfAnotherStoreIsForbiddenAndAnUnknownOneIsNotFound() throws Exception {
        UUID otherStore = fixtures.shop().store();

        mvc.perform(get(POS + "/orders/" + order)
                        .with(OrdersActors.pos(otherStore, employee, terminal, Permission.ORDER_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(POS + "/orders/" + UUID.randomUUID())
                        .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_VIEW)))
                .andExpect(status().isNotFound());
        fixtures.addLine(shop, order, """
                        {"itemId": "%s", "quantity": 1}""".formatted(shop.item()), employee, terminal,
                        UUID.randomUUID().toString());
        mvc.perform(put(POS + "/orders/" + order + "/lines/" + UUID.randomUUID())
                        .with(OrdersActors.pos(otherStore, employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\": 0, \"quantity\": 1}"))
                .andExpect(status().isForbidden());
    }

    ResultActions getOrder() throws Exception {
        return mvc.perform(get(POS + "/orders/" + order)
                .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_VIEW)));
    }

    ResultActions editLine(UUID line, String body) throws Exception {
        return mvc.perform(put(POS + "/orders/" + order + "/lines/" + line)
                .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions deleteLine(UUID line, String body) throws Exception {
        return mvc.perform(delete(POS + "/orders/" + order + "/lines/" + line)
                .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
