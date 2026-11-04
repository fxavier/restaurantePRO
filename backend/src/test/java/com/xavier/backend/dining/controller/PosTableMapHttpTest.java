package com.xavier.backend.dining.controller;

import static com.xavier.backend.dining.DiningActors.admin;
import static com.xavier.backend.dining.DiningActors.inStore;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.dining.DiningModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@DiningModuleTest
class PosTableMapHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DiningFixtures fixtures;

    UUID store;

    @BeforeEach
    void newStore() throws Exception {
        store = fixtures.newStore();
    }

    @Test
    void thePosMapShowsTheActiveAreasWithTheirActiveTablesOfThatStoreAllFree() throws Exception {
        UUID sala = fixtures.createArea(store, "SALA");
        UUID bar = fixtures.createArea(store, "BAR");
        fixtures.createTable(store, sala, "M2");
        fixtures.createTable(store, sala, "M1");
        UUID retired = fixtures.createTable(store, sala, "M9");
        deactivateTable(retired, sala, "M9");
        UUID closedArea = fixtures.createArea(store, "ESPL");
        fixtures.createTable(store, closedArea, "E1");
        deactivateArea(closedArea, "ESPL");
        UUID otherStore = fixtures.newStore();
        fixtures.createTable(otherStore, fixtures.createArea(otherStore, "SALA"), "X1");

        mvc.perform(get(DiningFixtures.POS_TABLES).param("storeId", store.toString())
                        .with(inStore(store, Permission.DINING_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.areas[*].code", contains("BAR", "SALA")))
                .andExpect(jsonPath("$.areas[0].id").value(bar.toString()))
                .andExpect(jsonPath("$.areas[0].tables.length()").value(0))
                .andExpect(jsonPath("$.areas[1].tables[*].code", contains("M1", "M2")))
                .andExpect(jsonPath("$.areas[1].tables[0].seats").value(4))
                .andExpect(jsonPath("$.areas[1].tables[*].status", everyItem(is("FREE"))));
    }

    @Test
    void thePosMapNeedsDiningViewInThatStore() throws Exception {
        UUID otherStore = fixtures.newStore();

        mvc.perform(get(DiningFixtures.POS_TABLES).param("storeId", store.toString())
                .with(inStore(otherStore, Permission.DINING_VIEW))).andExpect(status().isForbidden());
        mvc.perform(get(DiningFixtures.POS_TABLES).param("storeId", store.toString())
                .with(inStore(store, Permission.DINING_MANAGE))).andExpect(status().isForbidden());
    }

    @Test
    void thePosMapOfAnUnknownStoreIsNotFound() throws Exception {
        mvc.perform(get(DiningFixtures.POS_TABLES).param("storeId", UUID.randomUUID().toString())
                .with(admin(Permission.DINING_VIEW))).andExpect(status().isNotFound());
    }

    void deactivateTable(UUID table, UUID area, String code) throws Exception {
        mvc.perform(put(DiningFixtures.TABLES + "/" + table).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"areaId": "%s", "code": "%s", "seats": 4, "active": false, "version": 0}"""
                                .formatted(area, code)))
                .andExpect(status().isOk());
    }

    void deactivateArea(UUID area, String code) throws Exception {
        mvc.perform(put(DiningFixtures.AREAS + "/" + area).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "%s", "name": "Fechada", "active": false, "version": 0}""".formatted(code)))
                .andExpect(status().isOk());
    }
}
