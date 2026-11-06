package com.xavier.backend.promotions;

import static com.xavier.backend.catalog.Actors.inStore;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.catalog.Publications;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** Stores with a published catalog, and promotions built through the BackOffice HTTP API. Fresh codes every call. */
@TestComponent
public class PromotionsFixtures {

    public static final String PROMOTIONS = "/api/v1/backoffice/promotions";
    public static final String RELEASES = PROMOTIONS + "/releases";

    /** A store whose published catalog sells {@code item} (family {@code family}) at 12.50. */
    public record Shop(UUID store, UUID item, UUID family) {
    }

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    PromotionsFixtures(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    public Shop shop() throws Exception {
        Publications publications = new Publications(mvc, jdbc);
        Publications.SellableStore sellable = publications.sellableStore();
        publications.publishNew(sellable.store());
        return new Shop(sellable.store(), CatalogFixtures.id(sellable.item()),
                UUID.fromString(sellable.item().get("familyId").asString()));
    }

    public UUID storeWithoutCatalog() throws Exception {
        return new CatalogFixtures(mvc).store();
    }

    public ResultActions perform(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    public ResultActions create(UUID store, String body) throws Exception {
        return perform(post(PROMOTIONS).with(inStore(store, Permission.PROMOTION_MANAGE)), body);
    }

    /** A 20 % off promotion with priority {@code priority}, scoped by {@code scopeJson}, assigned to {@code store}. */
    public JsonNode promotion(UUID store, String code, String scopeJson) throws Exception {
        JsonNode created = json(expect(create(store, """
                {"code": "%s", "name": "Promo %s", "kind": "PERCENT_OFF", "percent": 20, "priority": 1}"""
                .formatted(code, code)), 201));
        JsonNode scoped = json(expect(perform(put(PROMOTIONS + "/" + id(created) + "/scope")
                .with(inStore(store, Permission.PROMOTION_MANAGE)), scopeJson.replace("}", ", \"version\": "
                + created.get("version").asLong() + "}")), 200));
        return json(expect(perform(put(PROMOTIONS + "/" + id(created) + "/stores")
                .with(inStore(store, Permission.PROMOTION_MANAGE)), """
                {"storeIds": ["%s"], "version": %d}""".formatted(store, scoped.get("version").asLong())), 200));
    }

    public ResultActions createRelease(UUID store) throws Exception {
        return perform(post(RELEASES).with(inStore(store, Permission.PROMOTION_MANAGE)),
                "{\"storeId\": \"%s\"}".formatted(store));
    }

    public ResultActions validate(UUID store, UUID release) throws Exception {
        return perform(post(RELEASES + "/" + release + "/validate").with(inStore(store, Permission.PROMOTION_MANAGE)),
                "");
    }

    public ResultActions publish(UUID store, UUID release) throws Exception {
        return perform(post(RELEASES + "/" + release + "/publish").with(inStore(store, Permission.PROMOTION_MANAGE)),
                "");
    }

    /** Creates, validates and publishes a release of {@code store}; returns its JSON view. */
    public JsonNode published(UUID store) throws Exception {
        UUID release = id(json(expect(createRelease(store), 201)));
        expect(validate(store, release), 200);
        return json(expect(publish(store, release), 200));
    }

    public static String code(String prefix) {
        return CatalogFixtures.code(prefix);
    }

    public static UUID id(JsonNode node) {
        return CatalogFixtures.id(node);
    }

    /** Like {@code andExpect(status().is(expected))}, but a failure shows the response body. */
    public static ResultActions expect(ResultActions result, int expected) throws Exception {
        int actual = result.andReturn().getResponse().getStatus();
        if (actual != expected) {
            throw new AssertionError("Status expected:<" + expected + "> but was:<" + actual + ">: "
                    + result.andReturn().getResponse().getContentAsString());
        }
        return result;
    }

    public static JsonNode json(ResultActions result) throws Exception {
        return CatalogFixtures.json(result.andReturn().getResponse().getContentAsString());
    }
}
