package com.xavier.backend.catalog;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.Actors.inStore;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Builds catalog data through the public HTTP API, the way the BackOffice does. Fresh codes every call. */
public final class CatalogFixtures {

    public static final String CATALOG = "/api/v1/backoffice/catalog";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc;

    public CatalogFixtures(MockMvc mvc) {
        this.mvc = mvc;
    }

    public static String code(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    public static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    public UUID store() throws Exception {
        return id(created(post("/api/v1/backoffice/stores").with(admin(Permission.STORE_MANAGE)), """
                {"code": "%s", "name": "Store", "timezone": "Africa/Maputo"}""".formatted(code("S"))));
    }

    public UUID family() throws Exception {
        return id(created(post(CATALOG + "/families").with(admin(Permission.CATALOG_MANAGE)), """
                {"code": "%s", "name": "Drinks"}""".formatted(code("F"))));
    }

    public UUID unit() throws Exception {
        return id(created(post(CATALOG + "/units").with(admin(Permission.CATALOG_MANAGE)), """
                {"code": "%s", "name": "Piece"}""".formatted(code("U"))));
    }

    /** A tax category with one rate already in effect. */
    public UUID taxCategory(String ratePercent) throws Exception {
        UUID category = id(created(post(CATALOG + "/tax-categories").with(admin(Permission.CATALOG_MANAGE)), """
                {"code": "%s", "name": "VAT"}""".formatted(code("T"))));
        created(post(CATALOG + "/tax-categories/" + category + "/rates").with(admin(Permission.CATALOG_MANAGE)), """
                {"ratePercent": %s, "effectiveFrom": "2000-01-01T00:00:00Z"}""".formatted(ratePercent));
        return category;
    }

    public JsonNode item(UUID family, UUID unit, UUID taxCategory) throws Exception {
        return created(post(CATALOG + "/items").with(admin(Permission.CATALOG_MANAGE)), """
                {"code": "%s", "name": "Coca-Cola 33cl", "familyId": "%s", "unitId": "%s", "taxCategoryId": "%s",
                 "productionDestination": "BAR", "soldByWeight": false}""".formatted(code("I"), family, unit, taxCategory));
    }

    /** An active item with its own family, unit and a 16% tax category. */
    public JsonNode item() throws Exception {
        return item(family(), unit(), taxCategory("16"));
    }

    public JsonNode variant(UUID item) throws Exception {
        return created(post(CATALOG + "/items/" + item + "/variants").with(admin(Permission.CATALOG_MANAGE)), """
                {"code": "%s", "name": "Large"}""".formatted(code("V")));
    }

    public UUID priceList(UUID store) throws Exception {
        return id(created(post(CATALOG + "/price-lists").with(inStore(store, Permission.CATALOG_MANAGE)), """
                {"storeId": "%s", "name": "Main"}""".formatted(store)));
    }

    public JsonNode price(UUID store, UUID priceList, UUID item, UUID variant, String amount) throws Exception {
        return created(post(CATALOG + "/price-lists/" + priceList + "/prices")
                .with(inStore(store, Permission.CATALOG_MANAGE)), """
                {"itemId": "%s", "variantId": %s, "amount": %s}""".formatted(item,
                variant == null ? "null" : "\"" + variant + "\"", amount));
    }

    public JsonNode created(MockHttpServletRequestBuilder request, String body) throws Exception {
        String response = mvc.perform(request.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json(response);
    }

    public static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asString());
    }
}
