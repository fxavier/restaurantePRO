package com.xavier.backend.orders.menus;

import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.OrderEventProbe;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.OrdersCatalog;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import com.xavier.backend.orders.model.event.ComponentLine;
import com.xavier.backend.orders.model.event.OrderBatchConfirmed;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * D108, P50: a MENU draft carries its choices; confirmation checks them against the menu's groups in the published
 * catalog and freezes them as unpriced components. Only the menu line is billed, at the menu price; production gets
 * each component on its own destination and inventory pulls them with the round. A43 (orders half).
 */
@OrdersModuleTest
class MenuConfirmationTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersCatalog catalog;

    @Autowired
    OrdersApi ordersApi;

    @Autowired
    OrderEventProbe events;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID soup;
    UUID salad;
    UUID juice;
    UUID menu;
    long catalogVersion;
    UUID order;

    @BeforeEach
    void aShopSellingAMenuOfAStarterAndADrink() throws Exception {
        shop = fixtures.shop();
        soup = id(catalog.item(shop, "KITCHEN", "4.00"));
        salad = id(catalog.item(shop, "KITCHEN", "5.00"));
        juice = id(catalog.item(shop, "BAR", "3.00"));
        menu = id(catalog.menu(shop, "9.50", OrdersCatalog.group("STARTER", 1, 1, 0, soup, salad),
                OrdersCatalog.group("DRINK", 0, 1, 1, juice)));
        catalogVersion = fixtures.republish(shop.store());
        order = OrdersFixtures.id(fixtures.openedOrder(shop, employee, terminal));
    }

    @AfterEach
    void retireTheMenu() throws Exception {
        catalog.removeCompanyWideData();
    }

    @Test
    void aMenuIsBilledOnceAndItsChoicesBecomeComponentsOnTheirDestinations() throws Exception {
        UUID line = OrdersFixtures.id(draftMenu("2", choice("STARTER", soup), choice("DRINK", juice))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.components.length()").value(2))
                .andReturn().getResponse().getContentAsString().transform(OrdersFixtures::json));

        JsonNode round = OrdersFixtures.json(confirm(line).andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].kind").value("MENU"))
                .andExpect(jsonPath("$.lines[0].gross.amount").value(19.00))
                .andExpect(jsonPath("$.lines[0].components[0].destination").value("KITCHEN"))
                .andExpect(jsonPath("$.lines[0].components[1].destination").value("BAR"))
                .andReturn().getResponse().getContentAsString());

        assertThat(ordersApi.billingView(order).lines()).singleElement()
                .satisfies(billed -> assertThat(billed.gross().amount()).isEqualByComparingTo("19.00"));
        OrderBatchConfirmed published = events.receivedFor(order, 1).getFirst();
        assertThat(published.lines().getFirst().components()).extracting(ComponentLine::destination,
                        component -> component.quantity().toPlainString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("KITCHEN", "2.000"),
                        org.assertj.core.groups.Tuple.tuple("BAR", "2.000"));
        BatchFacts facts = ordersApi.batchFacts(UUID.fromString(round.get("id").asString()));
        assertThat(facts.lines().getFirst().components()).extracting(BatchFacts.ComponentFact::itemId)
                .containsExactly(soup, juice);
        assertThat(facts.lines().getFirst().components()).allSatisfy(component ->
                assertThat(component.quantity()).isEqualByComparingTo("2"));
    }

    @Test
    void choicesOutsideTheMenuGroupsAreRefused() throws Exception {
        draftMenu("1", choice("DRINK", juice)) // no starter
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MENU_SELECTION_INVALID"));
        draftMenu("1", choice("STARTER", soup), choice("STARTER", salad)) // two starters, max 1
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MENU_SELECTION_INVALID"));
        draftMenu("1", choice("STARTER", juice)) // not an option of the group
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MENU_SELECTION_INVALID"));
    }

    @Test
    void aMenuWhoseGroupsChangedIsRefusedAtConfirmation() throws Exception {
        UUID line = OrdersFixtures.id(OrdersFixtures.json(draftMenu("1", choice("STARTER", soup))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()));
        fixtures.republish(shop.store()); // same groups, new catalog version: the POS re-reads
        catalog.removeCompanyWideData(); // the menu leaves the catalog (inactive)
        long withoutMenu = fixtures.republish(shop.store());

        fixtures.confirm(shop, order, """
                {"orderVersion": 0, "catalogVersion": %d, "lineIds": ["%s"]}""".formatted(withoutMenu, line),
                        employee, terminal)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_IN_CATALOG"));
    }

    ResultActions draftMenu(String quantity, String... choices) throws Exception {
        return fixtures.addLine(shop, order, """
                {"itemId": "%s", "quantity": %s, "components": [%s]}""".formatted(menu, quantity,
                String.join(",", choices)), employee, terminal, UUID.randomUUID().toString());
    }

    ResultActions confirm(UUID line) throws Exception {
        return fixtures.confirm(shop, order, """
                {"orderVersion": 0, "catalogVersion": %d, "lineIds": ["%s"]}""".formatted(catalogVersion, line),
                employee, terminal);
    }

    static String choice(String group, UUID item) {
        return "{\"groupCode\": \"%s\", \"itemId\": \"%s\"}".formatted(group, item);
    }

    static List<String> none() {
        return List.of();
    }
}
