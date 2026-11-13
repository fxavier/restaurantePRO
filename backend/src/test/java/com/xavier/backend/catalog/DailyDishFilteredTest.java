package com.xavier.backend.catalog;

import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.Actors.pos;
import static com.xavier.backend.catalog.CatalogFixtures.CATALOG;
import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.Publications.SellableStore;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * D147 daily dish row: a dish set while its item was published stays stored, but once a republish leaves the item
 * out (the store now excludes it), {@code CatalogApi.dailyDishes} and the POS read drop it.
 */
@CatalogModuleTest
class DailyDishFilteredTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 12);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CatalogApi catalogApi;

    @Test
    void aDailyDishAbsentFromTheCurrentSnapshotIsDropped() throws Exception {
        SellableStore sellable = publications().sellableStore();
        JsonNode soup = new CatalogFixtures(mvc).item();
        new CatalogFixtures(mvc).price(sellable.store(), sellable.priceList(), id(soup), null, "4.00");
        publications().publishNew(sellable.store());
        mvc.perform(put(CATALOG + "/daily-dishes").with(inStore(sellable.store(), Permission.CATALOG_AVAILABILITY))
                        .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"storeId": "%s", "businessDate": "%s", "itemIds": ["%s", "%s"]}"""
                                .formatted(sellable.store(), DAY, id(soup), id(sellable.item()))))
                .andExpect(status().isOk());

        new Exclusions(mvc).exclude(sellable.store(), id(soup), null).andExpect(status().isOk());
        publications().publishNew(sellable.store());

        assertThat(catalogApi.dailyDishes(sellable.store(), DAY)).containsExactly(id(sellable.item()));
        mvc.perform(get("/api/v1/pos/catalog/daily-dishes").param("date", DAY.toString())
                        .with(pos(sellable.store(), Permission.CATALOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemIds.length()").value(1))
                .andExpect(jsonPath("$.itemIds[0]").value(id(sellable.item()).toString()));
    }

    private Publications publications() {
        return new Publications(mvc, jdbc);
    }
}
