package com.xavier.backend.production.service;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.production.ProductionFixtures.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.orders.OrdersCatalog;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.production.ProductionFixtures;
import com.xavier.backend.production.ProductionModuleTest;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D108, A43 (production half): a menu's starter prints on the kitchen ticket and its drink on the bar ticket, each
 * under the menu line; a component nothing prepares is not printed (D56), and a component whose destination has no
 * route is kept on an UNROUTED ticket (D89 (1)). A void of the menu reaches the same stations, also after a
 * transfer (its components are the round line's). The routed printers are on HOLD, so no worker touches their jobs.
 */
@ProductionModuleTest
class MenuComponentRoutingTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProductionFixtures fixtures;

    @Autowired
    OrdersFixtures orders;

    @Autowired
    OrdersCatalog catalog;

    final UUID employee = UUID.randomUUID();
    final UUID terminal = UUID.randomUUID();
    Shop shop;
    UUID soup;
    UUID juice;
    UUID bread;
    UUID menu;
    long catalogVersion;

    @BeforeEach
    void aMenuOfAStarterADrinkAndBread() throws Exception {
        shop = fixtures.shop();
        soup = item("Sopa do dia", "KITCHEN");
        juice = item("Sumo de laranja", "BAR");
        bread = item("Pão da casa", null);
        menu = CatalogFixtures.id(catalog.menu(shop, "9.50", OrdersCatalog.group("STARTER", 1, 1, 0, soup),
                OrdersCatalog.group("DRINK", 1, 1, 1, juice), OrdersCatalog.group("BREAD", 1, 1, 2, bread)));
        catalogVersion = orders.republish(shop.store());
    }

    @AfterEach
    void retireTheMenu() throws Exception {
        catalog.removeCompanyWideData();
    }

    @Test
    void eachComponentPrintsUnderItsMenuOnItsOwnDestinationsTicket() throws Exception {
        UUID kitchen = held("K");
        UUID bar = held("B");
        fixtures.routes(shop.store(), Map.of("KITCHEN", kitchen, "BAR", bar)).andExpect(status().isOk());

        UUID batch = confirmedMenu("2").batch();

        await(() -> fixtures.tickets(batch).size() == 2, "a kitchen and a bar ticket");
        Map<Object, Map<String, Object>> tickets = byDestination(batch);
        assertThat(tickets).containsOnlyKeys("KITCHEN", "BAR");
        assertThat(tickets.get("KITCHEN")).containsEntry("printer_id", kitchen).containsEntry("routing", "ROUTED");
        assertThat((String) tickets.get("KITCHEN").get("content")).contains("2 x MENU item\n   + 2 x Sopa do dia")
                .doesNotContain("Sumo de laranja", "Pão da casa");
        assertThat(tickets.get("BAR")).containsEntry("printer_id", bar).containsEntry("routing", "ROUTED");
        assertThat((String) tickets.get("BAR").get("content")).contains("2 x MENU item\n   + 2 x Sumo de laranja")
                .doesNotContain("Sopa do dia", "Pão da casa");
        assertThat(fixtures.jobsOfSource(batch)).extracting(job -> job.get("printer_id"))
                .containsExactlyInAnyOrder(kitchen, bar);
    }

    @Test
    void aComponentWhoseDestinationHasNoRouteIsKeptUnrouted() throws Exception {
        UUID bar = held("B");
        fixtures.routes(shop.store(), Map.of("BAR", bar)).andExpect(status().isOk());

        Confirmed menu = confirmedMenu("1");
        UUID batch = menu.batch();

        await(() -> fixtures.tickets(batch).size() == 2, "a kitchen and a bar ticket");
        Map<Object, Map<String, Object>> tickets = byDestination(batch);
        assertThat(tickets.get("KITCHEN")).containsEntry("routing", "UNROUTED").containsEntry("printer_id", null);
        assertThat((String) tickets.get("KITCHEN").get("content")).contains("+ 1 x Sopa do dia");
        assertThat(tickets.get("BAR")).containsEntry("routing", "ROUTED");
        assertThat(fixtures.jobsOfSource(batch)).singleElement()
                .satisfies(job -> assertThat(job).containsEntry("printer_id", bar));

        UUID voidId = PosRequests.voided(mvc, jdbc, shop.store(), menu.order(), menu.line(), "1", approver());

        await(() -> fixtures.tickets(voidId).size() == 2, "a kitchen and a bar void ticket");
        Map<Object, Map<String, Object>> voids = byDestination(voidId);
        assertThat(voids.get("KITCHEN")).containsEntry("source_type", "VOID").containsEntry("routing", "UNROUTED");
        assertThat(voids.get("BAR")).containsEntry("routing", "ROUTED");
        assertThat(fixtures.jobsOfSource(voidId)).singleElement()
                .satisfies(job -> assertThat(job).containsEntry("printer_id", bar));
    }

    @Test
    void voidingAMenuPrintsAVoidTicketOnEachStationOfItsComponents() throws Exception {
        UUID kitchen = held("K");
        UUID bar = held("B");
        fixtures.routes(shop.store(), Map.of("KITCHEN", kitchen, "BAR", bar)).andExpect(status().isOk());
        Confirmed menu = confirmedMenu("3");

        UUID voidId = PosRequests.voided(mvc, jdbc, shop.store(), menu.order(), menu.line(), "2", approver());

        await(() -> fixtures.tickets(voidId).size() == 2, "a kitchen and a bar void ticket");
        Map<Object, Map<String, Object>> voids = byDestination(voidId);
        assertThat(voids.get("KITCHEN")).containsEntry("source_type", "VOID").containsEntry("printer_id", kitchen);
        assertThat((String) voids.get("KITCHEN").get("content")).contains(TicketLayouts.VOID_TITLE,
                "-2 x MENU item\n   + -2 x Sopa do dia").doesNotContain("Sumo de laranja", "Pão da casa");
        assertThat(voids.get("BAR")).containsEntry("printer_id", bar);
        assertThat((String) voids.get("BAR").get("content")).contains("-2 x MENU item\n   + -2 x Sumo de laranja")
                .doesNotContain("Sopa do dia", "Pão da casa");
    }

    @Test
    void voidingATransferredMenuStillReachesTheStationsOfItsComponents() throws Exception {
        UUID kitchen = held("K");
        UUID bar = held("B");
        fixtures.routes(shop.store(), Map.of("KITCHEN", kitchen, "BAR", bar)).andExpect(status().isOk());
        Confirmed menu = confirmedMenu("2");
        UUID target = orders.openedAt(shop, orders.newTable(shop, "T" + UUID.randomUUID().toString()
                .substring(0, 4)), employee, terminal);
        orders.transfer(shop, menu.order(), version(menu.order()), target, version(target),
                Map.of(menu.line(), "1"), false, employee, terminal, UUID.randomUUID().toString())
                .andExpect(status().isCreated());
        UUID copy = jdbc.sql("SELECT id FROM orders.order_line WHERE order_id = ?").param(target)
                .query(UUID.class).single();

        UUID voidId = PosRequests.voided(mvc, jdbc, shop.store(), target, copy, "1", approver());

        await(() -> fixtures.tickets(voidId).size() == 2, "a kitchen and a bar void ticket");
        assertThat((String) byDestination(voidId).get("KITCHEN").get("content")).contains("+ -1 x Sopa do dia");
        assertThat((String) byDestination(voidId).get("BAR").get("content")).contains("+ -1 x Sumo de laranja");
    }

    private IdentityFixtures.Employee approver() throws Exception {
        return orders.employee(shop, IdentityFixtures.MANAGER);
    }

    private long version(UUID order) {
        return jdbc.sql("SELECT version FROM orders.order_header WHERE id = ?").param(order).query(Long.class)
                .single();
    }

    /** A confirmed menu: its order, its line and its round. */
    private record Confirmed(UUID order, UUID line, UUID batch) {
    }

    private Confirmed confirmedMenu(String quantity) throws Exception {
        UUID order = OrdersFixtures.id(orders.openedOrder(shop, employee, terminal));
        UUID line = OrdersFixtures.id(OrdersFixtures.json(orders.addLine(shop, order, """
                        {"itemId": "%s", "quantity": %s, "components": [%s, %s, %s]}""".formatted(menu, quantity,
                        choice("STARTER", soup), choice("DRINK", juice), choice("BREAD", bread)), employee, terminal,
                UUID.randomUUID().toString()).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString()));
        orders.confirm(shop, order, 0, catalogVersion, java.util.List.of(line), employee, terminal,
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        return new Confirmed(order, line, PosRequests.lastBatch(jdbc, order));
    }

    private Map<Object, Map<String, Object>> byDestination(UUID batch) {
        return fixtures.tickets(batch).stream().collect(Collectors.toMap(ticket -> ticket.get("destination"),
                Function.identity()));
    }

    /** A printer of the shop on HOLD. */
    private UUID held(String prefix) throws Exception {
        UUID printer = fixtures.printer(shop.store(), prefix + UUID.randomUUID().toString().substring(0, 6),
                "127.0.0.1", 9);
        fixtures.mode(shop.store(), printer, "HOLD");
        return printer;
    }

    /** An active STANDARD item named {@code name}, priced in the shop. */
    private UUID item(String name, String destination) throws Exception {
        CatalogFixtures catalogFixtures = new CatalogFixtures(mvc);
        UUID item = CatalogFixtures.id(catalogFixtures.created(post(CatalogFixtures.CATALOG + "/items")
                .with(admin(Permission.CATALOG_MANAGE)), """
                {"code": "%s", "name": "%s", "familyId": "%s", "unitId": "%s", "taxCategoryId": "%s",
                 "productionDestination": %s, "soldByWeight": false, "kind": "STANDARD"}""".formatted(
                CatalogFixtures.code("I"), name, catalogFixtures.family(), catalogFixtures.unit(),
                catalogFixtures.taxCategory("16"), destination == null ? "null" : "\"" + destination + "\"")));
        catalogFixtures.price(shop.store(), shop.priceList(), item, null, "1.00");
        return item;
    }

    private static String choice(String group, UUID item) {
        return "{\"groupCode\": \"%s\", \"itemId\": \"%s\"}".formatted(group, item);
    }
}
