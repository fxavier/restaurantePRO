package com.xavier.backend.orders.menus;

import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.orders.OrdersCatalog;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.orders.OrdersModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D109, D118: the sold-out flag is operational (outside the release) and checked at confirmation: an item-level flag
 * covers every variant, a menu component counts too, and clearing the flag lets the same draft through.
 */
@OrdersModuleTest
class SoldOutAtConfirmationTest {

    @Autowired
    OrdersFixtures fixtures;

    @Autowired
    OrdersCatalog catalog;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID order;

    @BeforeEach
    void anOpenOrder() throws Exception {
        shop = fixtures.shop();
        order = OrdersFixtures.id(fixtures.openedOrder(shop, employee, terminal));
    }

    @AfterEach
    void retireTheMenus() throws Exception {
        catalog.removeCompanyWideData();
    }

    @Test
    void aSoldOutItemRefusesTheRoundUntilItIsAvailableAgain() throws Exception {
        UUID line = OrdersFixtures.id(fixtures.draft(shop, order, "1", employee, terminal));
        catalog.soldOut(shop, shop.item(), null, true);

        confirm(shop.catalogVersion(), line)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ITEM_SOLD_OUT"));

        catalog.soldOut(shop, shop.item(), null, false);
        confirm(shop.catalogVersion(), line).andExpect(status().isCreated());
    }

    @Test
    void aSoldOutComponentRefusesItsMenu() throws Exception {
        UUID soup = id(catalog.item(shop, "KITCHEN", "4.00"));
        UUID menu = id(catalog.menu(shop, "9.50", OrdersCatalog.group("STARTER", 1, 1, 0, soup)));
        long version = fixtures.republish(shop.store());
        UUID line = OrdersFixtures.id(OrdersFixtures.json(fixtures.addLine(shop, order, """
                {"itemId": "%s", "quantity": 1, "components": [{"groupCode": "STARTER", "itemId": "%s"}]}"""
                .formatted(menu, soup), employee, terminal, UUID.randomUUID().toString())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()));
        catalog.soldOut(shop, soup, null, true);

        confirm(version, line)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ITEM_SOLD_OUT"));
    }

    org.springframework.test.web.servlet.ResultActions confirm(long catalogVersion, UUID line) throws Exception {
        return fixtures.confirm(shop, order, """
                {"orderVersion": 0, "catalogVersion": %d, "lineIds": ["%s"]}""".formatted(catalogVersion, line),
                employee, terminal);
    }
}
