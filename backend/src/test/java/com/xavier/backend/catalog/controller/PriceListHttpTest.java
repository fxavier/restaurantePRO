package com.xavier.backend.catalog.controller;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.CatalogFixtures.CATALOG;
import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.catalog.CatalogModuleTest;
import com.xavier.backend.catalog.Race;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@CatalogModuleTest
class PriceListHttpTest {

    static final String PRICE_LISTS = CATALOG + "/price-lists";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aStoreCatalogManagerCreatesTheStorePriceListInTheCompanyCurrency() throws Exception {
        UUID store = fixtures().store();

        UUID list = fixtures().priceList(store);

        mvc.perform(get(PRICE_LISTS + "/" + list).with(inStore(store, Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.currency").value("MZN"));
        mvc.perform(get(PRICE_LISTS).param("storeId", store.toString()).with(inStore(store, Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'PRICE_LIST_CREATED'"
                        + " AND entity_id = :id AND store_id = :store")
                .param("id", list.toString()).param("store", store).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aStoreHasOnePriceListAlsoWhenTwoRequestsRace() throws Exception {
        UUID store = fixtures().store();
        String body = "{\"storeId\": \"%s\", \"name\": \"Main\"}".formatted(store);

        assertThat(Race.twice(() -> mvc.perform(post(PRICE_LISTS).with(inStore(store, Permission.CATALOG_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus())).containsExactlyInAnyOrder(201, 409);
    }

    @Test
    void aPriceListOfAnUnknownStoreBreaksARule() throws Exception {
        mvc.perform(post(PRICE_LISTS).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\", \"name\": \"Main\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aManagerOfAnotherStoreCannotTouchThisStoresPrices() throws Exception {
        UUID store = fixtures().store();
        UUID other = fixtures().store();
        UUID list = fixtures().priceList(store);
        UUID item = id(fixtures().item());

        mvc.perform(post(PRICE_LISTS).with(inStore(other, Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\", \"name\": \"Main\"}".formatted(store)))
                .andExpect(status().isForbidden());
        mvc.perform(get(PRICE_LISTS + "/" + list).with(inStore(other, Permission.CATALOG_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(post(PRICE_LISTS + "/" + list + "/prices").with(inStore(other, Permission.CATALOG_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\": \"%s\", \"amount\": 10}".formatted(item)))
                .andExpect(status().isForbidden());
        mvc.perform(post(PRICE_LISTS + "/" + list + "/prices").with(inStore(store, Permission.CATALOG_VIEW))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\": \"%s\", \"amount\": 10}".formatted(item)))
                .andExpect(status().isForbidden());
    }

    @Test
    void itemsAndVariantsArePricedAsMoneyOncePerList() throws Exception {
        UUID store = fixtures().store();
        UUID list = fixtures().priceList(store);
        UUID item = id(fixtures().item());
        UUID variant = id(fixtures().variant(item));

        JsonNode price = fixtures().price(store, list, item, null, "12.5");
        fixtures().price(store, list, item, variant, "15.00");

        assertThat(price.get("price").get("amount").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(price.get("price").get("amount").asString()).isEqualTo("12.50");
        assertThat(price.get("price").get("currency").asString()).isEqualTo("MZN");
        mvc.perform(post(PRICE_LISTS + "/" + list + "/prices").with(inStore(store, Permission.CATALOG_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\": \"%s\", \"amount\": 13}".formatted(item)))
                .andExpect(status().isConflict());
        mvc.perform(get(PRICE_LISTS + "/" + list + "/prices").with(inStore(store, Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'ITEM_PRICE_SET' AND store_id = :store")
                .param("store", store).query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void anAmountFinerThanTheCurrencyOrAWrongReferenceBreaksARule() throws Exception {
        UUID store = fixtures().store();
        UUID list = fixtures().priceList(store);
        UUID item = id(fixtures().item());
        UUID foreignVariant = id(fixtures().variant(id(fixtures().item())));

        for (String body : new String[] {
                "{\"itemId\": \"%s\", \"amount\": 1.005}".formatted(item),
                "{\"itemId\": \"%s\", \"amount\": 1}".formatted(UUID.randomUUID()),
                "{\"itemId\": \"%s\", \"variantId\": \"%s\", \"amount\": 1}".formatted(item, foreignVariant)}) {
            mvc.perform(post(PRICE_LISTS + "/" + list + "/prices").with(inStore(store, Permission.CATALOG_MANAGE))
                            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableContent());
        }
        mvc.perform(post(PRICE_LISTS + "/" + list + "/prices").with(inStore(store, Permission.CATALOG_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\": \"%s\", \"amount\": -1}".formatted(item)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPriceChangesWithItsCurrentVersionAndAStaleChangeIsAConflict() throws Exception {
        UUID store = fixtures().store();
        UUID list = fixtures().priceList(store);
        JsonNode price = fixtures().price(store, list, id(fixtures().item()), null, "10");
        String change = PRICE_LISTS + "/" + list + "/prices/" + id(price);

        mvc.perform(put(change).with(inStore(store, Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 11, \"version\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price.amount").value(11.00))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(change).with(inStore(store, Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 12, \"version\": 0}"))
                .andExpect(status().isConflict());
        mvc.perform(put(PRICE_LISTS + "/" + list).with(inStore(store, Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Summer\", \"version\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Summer"));
    }

    private CatalogFixtures fixtures() {
        return new CatalogFixtures(mvc);
    }
}
