package com.xavier.backend.dining.controller;

import static com.xavier.backend.dining.DiningActors.admin;
import static com.xavier.backend.dining.DiningActors.inStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.dining.DiningModuleTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@DiningModuleTest
class RestaurantTableHttpTest {

    static final String TABLES = DiningFixtures.TABLES;

    @Autowired
    MockMvc mvc;

    @Autowired
    DiningFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    UUID store;
    UUID area;

    @BeforeEach
    void newStoreWithAnArea() throws Exception {
        store = fixtures.newStore();
        area = fixtures.createArea(store, "SALA");
    }

    @Test
    void aStoreManagerCreatesATableInAnAreaOfTheStoreAndItIsAudited() throws Exception {
        JsonNode table = DiningFixtures.JSON.readTree(mvc.perform(post(TABLES)
                        .with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DiningFixtures.newTable(store, area, "M1", 4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.areaId").value(area.toString()))
                .andExpect(jsonPath("$.code").value("M1"))
                .andExpect(jsonPath("$.seats").value(4))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString());

        assertThat(fixtures.audits("RESTAURANT_TABLE_CREATED", table.get("id").asString(), store)).isEqualTo(1);
    }

    @Test
    void aTableInStoreAWithAnAreaOfStoreBIsRejected() throws Exception {
        UUID otherStore = fixtures.newStore();
        UUID foreignArea = fixtures.createArea(otherStore, "ESPL");

        mvc.perform(post(TABLES).with(admin(Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DiningFixtures.newTable(store, foreignArea, "M1", 4)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        assertThat(jdbc.sql("SELECT count(*) FROM dining.restaurant_table WHERE store_id = :store")
                .param("store", store).query(Long.class).single()).isZero();
    }

    @Test
    void aTableCannotBeMovedToAnAreaOfAnotherStore() throws Exception {
        UUID table = fixtures.createTable(store, area, "M1");
        UUID foreignArea = fixtures.createArea(fixtures.newStore(), "ESPL");

        mvc.perform(put(TABLES + "/" + table).with(admin(Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update(foreignArea, "M1", 4, true, 0)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anUnknownAreaIsRejectedLikeAForeignOne() throws Exception {
        mvc.perform(post(TABLES).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DiningFixtures.newTable(store, UUID.randomUUID(), "M1", 4)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anUpdateMovesTheTableWithinItsStoreAndDeactivatesItAndIsAudited() throws Exception {
        UUID table = fixtures.createTable(store, area, "M1");
        UUID terrace = fixtures.createArea(store, "ESPL");

        mvc.perform(put(TABLES + "/" + table).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update(terrace, "E1", 6, false, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areaId").value(terrace.toString()))
                .andExpect(jsonPath("$.code").value("E1"))
                .andExpect(jsonPath("$.seats").value(6))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(fixtures.audits("RESTAURANT_TABLE_UPDATED", table.toString(), store)).isEqualTo(1);
    }

    @Test
    void anUpdateWithAStaleVersionIsAConflict() throws Exception {
        UUID table = fixtures.createTable(store, area, "M1");
        mvc.perform(put(TABLES + "/" + table).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(update(area, "M1", 2, true, 0))).andExpect(status().isOk());

        mvc.perform(put(TABLES + "/" + table).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update(area, "M1", 8, true, 0)))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        mvc.perform(get(TABLES + "/" + table).with(inStore(store, Permission.DINING_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats").value(2));
    }

    @Test
    void anotherStoresManagerCannotSeeOrChangeThisStoresTables() throws Exception {
        UUID table = fixtures.createTable(store, area, "M1");
        UUID otherStore = fixtures.newStore();

        mvc.perform(post(TABLES).with(inStore(otherStore, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DiningFixtures.newTable(store, area, "M2", 2)))
                .andExpect(status().isForbidden());
        mvc.perform(get(TABLES).param("storeId", store.toString())
                .with(inStore(otherStore, Permission.DINING_MANAGE))).andExpect(status().isForbidden());
        mvc.perform(get(TABLES + "/" + table).with(inStore(otherStore, Permission.DINING_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(put(TABLES + "/" + table).with(inStore(otherStore, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update(area, "M1", 4, true, 0)))
                .andExpect(status().isForbidden());
        mvc.perform(get(TABLES + "/" + table).with(inStore(store, Permission.DINING_VIEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    void tablesAreListedPerStorePagedByCode() throws Exception {
        fixtures.createTable(store, area, "M2");
        fixtures.createTable(store, area, "M1");
        UUID otherStore = fixtures.newStore();
        fixtures.createTable(otherStore, fixtures.createArea(otherStore, "SALA"), "M3");

        mvc.perform(get(TABLES).param("storeId", store.toString()).param("size", "1")
                        .with(inStore(store, Permission.DINING_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].code").value("M1"));
    }

    @Test
    void tableCodesAreUniqueWithinAStoreEvenAcrossAreas() throws Exception {
        fixtures.createTable(store, area, "M1");
        UUID terrace = fixtures.createArea(store, "ESPL");

        mvc.perform(post(TABLES).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DiningFixtures.newTable(store, terrace, "M1", 2)))
                .andExpect(status().isConflict());
    }

    @Test
    void renamingATableToACodeInUseIsAConflict() throws Exception {
        fixtures.createTable(store, area, "M1");
        UUID second = fixtures.createTable(store, area, "M2");

        mvc.perform(put(TABLES + "/" + second).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update(area, "M1", 4, true, 0)))
                .andExpect(status().isConflict());
    }

    @Test
    void concurrentCreatesWithTheSameCodeGiveOneTableAndOneConflict() throws Exception {
        for (int round = 0; round < 10; round++) {
            String code = "R" + round;
            List<Integer> statuses = DiningFixtures.race(() -> mvc.perform(post(TABLES)
                            .with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(DiningFixtures.newTable(store, area, code, 2)))
                    .andReturn().getResponse().getStatus());

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(201, 409);
        }
    }

    @Test
    void aTableSeatsAtLeastOnePerson() throws Exception {
        mvc.perform(post(TABLES).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DiningFixtures.newTable(store, area, "M1", 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("seats"));
    }

    @Test
    void aTableNeedsAnExistingStore() throws Exception {
        mvc.perform(post(TABLES).with(admin(Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DiningFixtures.newTable(UUID.randomUUID(), area, "M1", 2)))
                .andExpect(status().isNotFound());
    }

    static String update(UUID areaId, String code, int seats, boolean active, long version) {
        return """
                {"areaId": "%s", "code": "%s", "seats": %d, "active": %s, "version": %d}"""
                .formatted(areaId, code, seats, active, version);
    }
}
