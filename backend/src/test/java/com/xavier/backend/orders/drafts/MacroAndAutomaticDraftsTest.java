package com.xavier.backend.orders.drafts;

import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersCatalog;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * D109: a macro adds its drafts in one command; opening a table adds the per-guest automatic items (quantity ×
 * guests) and opening any order the ORDER_OPEN items of its mode. Automatic drafts are flagged and behave as any draft.
 */
@OrdersModuleTest
class MacroAndAutomaticDraftsTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersCatalog catalog;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;

    @BeforeEach
    void aShop() throws Exception {
        shop = fixtures.shop();
    }

    @AfterEach
    void removeTheMacros() throws Exception {
        catalog.removeCompanyWideData();
    }

    @Test
    void aMacroAddsItsEntriesAsDraftsInOrder() throws Exception {
        UUID water = id(catalog.item(shop, "BAR", "1.00"));
        String macro = catalog.macro("""
                [{"itemId": "%s", "quantity": 2}, {"itemId": "%s", "quantity": 1}]""".formatted(water, shop.item()));
        long version = fixtures.republish(shop.store());
        UUID order = OrdersFixtures.id(fixtures.openedOrder(shop, employee, terminal));

        JsonNode view = OrdersFixtures.json(macro(order, 0, macro).andExpect(status().isCreated())
                .andExpect(jsonPath("$.catalogVersion").value(version))
                .andReturn().getResponse().getContentAsString());

        assertThat(StreamSupport.stream(view.get("lines").spliterator(), false)
                .map(line -> line.get("itemId").asString() + "x" + line.get("quantity").decimalValue().stripTrailingZeros().toPlainString()).toList())
                .containsExactly(water + "x2", shop.item() + "x1");
    }

    @Test
    void anUnknownMacroOrAStaleOrderIsRefused() throws Exception {
        UUID order = OrdersFixtures.id(fixtures.openedOrder(shop, employee, terminal));

        macro(order, 0, "NOPE").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MACRO_NOT_PUBLISHED"));
        macro(order, 7, "NOPE").andExpect(status().isConflict());
    }

    @Test
    void openingATableAddsThePerGuestItemsAndOpeningAnyOrderItsModesItems() throws Exception {
        UUID cover = id(catalog.item(shop, null, "0.50"));
        UUID bag = id(catalog.item(shop, null, "0.20"));
        catalog.automatic(shop, "TABLE_OPEN_PER_GUEST", null, cover, "1");
        catalog.automatic(shop, "ORDER_OPEN", "TAKEAWAY", bag, "1");
        fixtures.republish(shop.store());

        JsonNode table = fixtures.openedOrder(shop, employee, terminal); // 2 guests
        JsonNode takeaway = OrdersFixtures.json(mvc.perform(post(OrdersFixtures.POS + "/orders")
                        .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"TAKEAWAY\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        assertThat(table.get("lines")).singleElement().satisfies(line -> {
            assertThat(line.get("itemId").asString()).isEqualTo(cover.toString());
            assertThat(line.get("quantity").decimalValue()).isEqualByComparingTo("2");
            assertThat(line.get("automatic").asBoolean()).isTrue();
            assertThat(line.get("status").asString()).isEqualTo("DRAFT");
        });
        assertThat(takeaway.get("lines")).singleElement()
                .satisfies(line -> assertThat(line.get("itemId").asString()).isEqualTo(bag.toString()));
    }

    @Test
    void anOrderOpensInAStoreWithoutAPublishedCatalog() throws Exception {
        UUID store = new com.xavier.backend.catalog.CatalogFixtures(mvc).store();

        mvc.perform(post(OrdersFixtures.POS + "/orders")
                        .with(OrdersActors.pos(store, employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\": \"COUNTER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines.length()").value(0));
    }

    ResultActions macro(UUID order, long version, String code) throws Exception {
        return mvc.perform(post(OrdersFixtures.POS + "/orders/" + order + "/lines/macro")
                .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderVersion\": %d, \"macroCode\": \"%s\"}".formatted(version, code)));
    }
}
