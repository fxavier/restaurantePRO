package com.xavier.backend.catalog;

import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.CatalogFixtures.CATALOG;
import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** Takes a store from an empty catalog to a published release through the BackOffice HTTP API. */
public final class Publications {

    public static final String RELEASES = CATALOG + "/releases";

    /** A store with a price list and one active, priced item ({@code 12.50}) with a 16% tax rate in effect. */
    public record SellableStore(UUID store, UUID priceList, JsonNode item, JsonNode price) {
    }

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final CatalogFixtures fixtures;

    public Publications(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.fixtures = new CatalogFixtures(mvc);
    }

    public SellableStore sellableStore() throws Exception {
        UUID store = fixtures.store();
        UUID priceList = fixtures.priceList(store);
        JsonNode item = fixtures.item();
        JsonNode price = fixtures.price(store, priceList, id(item), null, "12.50");
        // the shared database holds active items of other tests; give each of them a price in this store
        jdbc.sql("""
                        INSERT INTO catalog.item_price (id, price_list_id, item_id, variant_id, amount, currency, version,
                                                        created_at, updated_at)
                        SELECT gen_random_uuid(), :list, i.id, NULL, 1.00, 'MZN', 0, now(), now()
                        FROM catalog.item i WHERE i.id <> :item
                        ON CONFLICT DO NOTHING""").param("list", priceList).param("item", id(item)).update();
        return new SellableStore(store, priceList, item, price);
    }

    public JsonNode createDraft(UUID store) throws Exception {
        return fixtures.created(post(RELEASES).with(inStore(store, Permission.CATALOG_MANAGE)),
                "{\"storeId\": \"%s\"}".formatted(store));
    }

    public UUID validatedRelease(UUID store) throws Exception {
        UUID release = id(createDraft(store));
        mvc.perform(post(RELEASES + "/" + release + "/validate").with(inStore(store, Permission.CATALOG_MANAGE))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VALIDATED"));
        return release;
    }

    public ResultActions publish(UUID store, UUID release, String key, String body) throws Exception {
        return mvc.perform(post(RELEASES + "/" + release + "/publish").with(inStore(store, Permission.CATALOG_PUBLISH))
                .with(csrf()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Validates and publishes a new release of {@code store}; returns its id. */
    public UUID publishNew(UUID store) throws Exception {
        UUID release = validatedRelease(store);
        publish(store, release, UUID.randomUUID().toString(), "{}").andExpect(status().isOk());
        return release;
    }
}
