package com.xavier.backend.orders;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.Actors.inStore;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * R4 catalog data for an orders {@link OrdersFixtures.Shop}, built through the catalog HTTP APIs (S38): menus,
 * macros, automatic items and the sold-out flag. Menus and macros are company-wide and would block every other
 * store's publication once a test is done, so {@link #removeCompanyWideData} must run after each test.
 */
@TestComponent
public class OrdersCatalog {

    static final String CATALOG = CatalogFixtures.CATALOG;

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final List<UUID> macros = new ArrayList<>();
    private final List<UUID> menus = new ArrayList<>();

    OrdersCatalog(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    /** An active STANDARD item routed to {@code destination}, priced {@code price} in the shop's price list. */
    public JsonNode item(OrdersFixtures.Shop shop, String destination, String price) throws Exception {
        JsonNode item = fixtures().item("STANDARD", destination);
        fixtures().price(shop.store(), shop.priceList(), CatalogFixtures.id(item), null, price);
        return item;
    }

    /** A MENU priced {@code price}, with one group per {@code groupsJson} entry (code, name, min, max, options). */
    public JsonNode menu(OrdersFixtures.Shop shop, String price, String... groupsJson) throws Exception {
        JsonNode menu = fixtures().item("MENU", null);
        menus.add(CatalogFixtures.id(menu));
        fixtures().price(shop.store(), shop.priceList(), CatalogFixtures.id(menu), null, price);
        for (String group : groupsJson) {
            created(post(CATALOG + "/items/" + CatalogFixtures.id(menu) + "/menu-groups")
                    .with(admin(Permission.CATALOG_MANAGE)), group);
        }
        return menu;
    }

    /** {@code {"code", "name", "minChoices", "maxChoices", "position", "options": [{itemId, quantity}]}}. */
    public static String group(String code, int min, int max, int position, UUID... options) {
        return """
                {"code": "%s", "name": "%s", "minChoices": %d, "maxChoices": %d, "position": %d, "options": [%s]}"""
                .formatted(code, code, min, max, position, String.join(",", java.util.Arrays.stream(options)
                        .map(option -> "{\"itemId\": \"%s\", \"quantity\": 1}".formatted(option)).toList()));
    }

    /** A company-wide macro of {@code entriesJson}; returns its code. */
    public String macro(String entriesJson) throws Exception {
        String code = CatalogFixtures.code("M");
        JsonNode macro = created(post(CATALOG + "/macros").with(admin(Permission.CATALOG_MANAGE)), """
                {"code": "%s", "name": "Macro %s", "entries": %s}""".formatted(code, code, entriesJson));
        macros.add(CatalogFixtures.id(macro));
        return code;
    }

    /** An active automatic item of the shop's store. */
    public void automatic(OrdersFixtures.Shop shop, String trigger, String mode, UUID item, String quantity)
            throws Exception {
        created(post(CATALOG + "/automatic-items").with(inStore(shop.store(), Permission.CATALOG_MANAGE)),
                """
                {"storeId": "%s", "trigger": "%s", "mode": %s, "itemId": "%s", "quantity": %s}""".formatted(
                        shop.store(), trigger, mode == null ? "null" : "\"" + mode + "\"", item, quantity));
    }

    /** Flags {@code item} (or one of its variants) sold out in the shop's store, from a POS (D109). */
    public void soldOut(OrdersFixtures.Shop shop, UUID item, UUID variant, boolean soldOut) throws Exception {
        mvc.perform(withBody(put("/api/v1/pos/catalog/availability/" + item)
                        .with(OrdersActors.pos(shop.store(), UUID.randomUUID(), UUID.randomUUID(),
                                Permission.CATALOG_AVAILABILITY)), """
                        {"variantId": %s, "soldOut": %s, "reason": "out"}""".formatted(
                        variant == null ? "null" : "\"" + variant + "\"", soldOut)))
                .andExpect(status().isOk());
    }

    /** Removes this test's macros and retires its menus, whatever its outcome. */
    public void removeCompanyWideData() throws Exception {
        for (UUID macro : macros) {
            jdbc.sql("DELETE FROM catalog.quick_page_entry WHERE macro_id = ?").param(macro).update();
            mvc.perform(delete(CATALOG + "/macros/" + macro).param("version", "0")
                    .with(admin(Permission.CATALOG_MANAGE)).with(csrf()));
        }
        macros.clear();
        if (!menus.isEmpty()) {
            jdbc.sql("UPDATE catalog.item SET availability = 'INACTIVE', version = version + 1 WHERE id IN (:ids)")
                    .param("ids", menus).update();
        }
        menus.clear();
    }

    private JsonNode created(MockHttpServletRequestBuilder request, String body) throws Exception {
        return CatalogFixtures.json(mvc.perform(withBody(request, body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private CatalogFixtures fixtures() {
        return new CatalogFixtures(mvc);
    }

    private static MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder request, String body) {
        return request.with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static RequestPostProcessor manager(UUID store) {
        return inStore(store, Permission.CATALOG_MANAGE);
    }
}
