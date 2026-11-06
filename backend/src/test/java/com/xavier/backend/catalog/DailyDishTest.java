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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * D109: the daily dishes of a store for a business date are operational (outside the release) and must be items of
 * the store's published release. Setting them replaces the day's list, in order.
 */
@CatalogModuleTest
class DailyDishTest {

    static final String BACKOFFICE = CATALOG + "/daily-dishes";
    static final String POS = "/api/v1/pos/catalog/daily-dishes";
    static final LocalDate DAY = LocalDate.of(2026, 11, 6);

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CatalogApi catalogApi;

    @Test
    void theDailyDishesOfADateAreReplacedInOrderAndReadByThePos() throws Exception {
        SellableStore sellable = publications().sellableStore();
        JsonNode soup = fixtures().item();
        JsonNode fish = fixtures().item();
        fixtures().price(sellable.store(), sellable.priceList(), id(soup), null, "4.00");
        fixtures().price(sellable.store(), sellable.priceList(), id(fish), null, "9.00");
        publications().publishNew(sellable.store());

        setDishes(sellable.store(), DAY, id(fish), id(soup), id(sellable.item()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemIds[0]").value(id(fish).toString()));
        setDishes(sellable.store(), DAY, id(soup), id(fish)).andExpect(status().isOk());
        setDishes(sellable.store(), DAY.plusDays(1), id(sellable.item())).andExpect(status().isOk());

        assertThat(catalogApi.dailyDishes(sellable.store(), DAY)).containsExactly(id(soup), id(fish));
        assertThat(catalogApi.dailyDishes(sellable.store(), DAY.plusDays(1))).containsExactly(id(sellable.item()));
        assertThat(catalogApi.dailyDishes(sellable.store(), DAY.minusDays(1))).isEmpty();
        mvc.perform(get(POS).param("date", DAY.toString()).with(pos(sellable.store(), Permission.CATALOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(sellable.store().toString()))
                .andExpect(jsonPath("$.businessDate").value(DAY.toString()))
                .andExpect(jsonPath("$.itemIds.length()").value(2))
                .andExpect(jsonPath("$.itemIds[0]").value(id(soup).toString()));
        mvc.perform(get(POS).with(pos(sellable.store(), Permission.CATALOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").isNotEmpty()); // without a date: the store's business day now
    }

    @Test
    void concurrentReplacementsLeaveExactlyOneOfTheLists() throws Exception {
        SellableStore sellable = publications().sellableStore();
        JsonNode soup = fixtures().item();
        fixtures().price(sellable.store(), sellable.priceList(), id(soup), null, "4.00");
        publications().publishNew(sellable.store());
        List<UUID> first = List.of(id(soup), id(sellable.item()));
        List<UUID> second = List.of(id(sellable.item()), id(soup));
        for (int round = 0; round < 3; round++) {
            LocalDate day = DAY.plusDays(10 + round);

            List<Integer> statuses = Race.run(List.<java.util.concurrent.Callable<Integer>>of(
                    () -> setDishes(sellable.store(), day, first.toArray(UUID[]::new)).andReturn().getResponse()
                            .getStatus(),
                    () -> setDishes(sellable.store(), day, second.toArray(UUID[]::new)).andReturn().getResponse()
                            .getStatus()));

            assertThat(statuses).as("round %d", round).containsExactly(200, 200);
            assertThat(catalogApi.dailyDishes(sellable.store(), day)).isIn(first, second);
        }
    }

    @Test
    void aDishOutsideThePublishedReleaseIsRefused() throws Exception {
        SellableStore sellable = publications().sellableStore();
        publications().publishNew(sellable.store());
        JsonNode unpublished = fixtures().item(); // created after the publication

        setDishes(sellable.store(), DAY, id(sellable.item()), id(unpublished))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_PUBLISHED"))
                .andExpect(jsonPath("$.itemIds[0]").value(id(unpublished).toString()));
        assertThat(catalogApi.dailyDishes(sellable.store(), DAY)).isEmpty();

        UUID neverPublished = fixtures().store();
        setDishes(neverPublished, DAY, id(sellable.item()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_PUBLISHED"));
    }

    @Test
    void settingDishesNeedsCatalogAvailabilityInThatStoreAndAKey() throws Exception {
        SellableStore sellable = publications().sellableStore();
        publications().publishNew(sellable.store());
        UUID other = fixtures().store();
        String body = body(sellable.store(), DAY, id(sellable.item()));

        mvc.perform(put(BACKOFFICE).with(inStore(other, Permission.CATALOG_AVAILABILITY)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(put(BACKOFFICE).with(inStore(sellable.store(), Permission.CATALOG_AVAILABILITY)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(put(BACKOFFICE).with(inStore(sellable.store(), Permission.CATALOG_AVAILABILITY)).with(csrf())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(sellable.store(), DAY, id(sellable.item()), id(sellable.item()))))
                .andExpect(status().isUnprocessableContent()); // the same dish twice
        mvc.perform(get(POS).param("date", DAY.toString()).with(pos(sellable.store(), Permission.DINING_VIEW)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'DAILY_DISHES_SET' AND store_id = :s")
                .param("s", sellable.store()).query(Long.class).single()).isZero();
    }

    private ResultActions setDishes(UUID store, LocalDate date, UUID... items) throws Exception {
        return mvc.perform(put(BACKOFFICE).with(inStore(store, Permission.CATALOG_AVAILABILITY)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body(store, date, items)));
    }

    private static String body(UUID store, LocalDate date, UUID... items) {
        return "{\"storeId\": \"%s\", \"businessDate\": \"%s\", \"itemIds\": [%s]}".formatted(store, date,
                String.join(",", java.util.Arrays.stream(items).map(item -> "\"" + item + "\"").toList()));
    }

    private CatalogFixtures fixtures() {
        return new CatalogFixtures(mvc);
    }

    private Publications publications() {
        return new Publications(mvc, jdbc);
    }
}
