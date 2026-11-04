package com.xavier.backend.dining.controller;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.dining.DiningModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** A table or area with an open occupation cannot be deactivated (r2-decisions §4 dining, R1 follow-up). */
@DiningModuleTest
class OccupiedDeactivationHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DiningFixtures fixtures;

    UUID store;
    UUID area;
    UUID table;

    @BeforeEach
    void aSeatedTable() throws Exception {
        store = fixtures.newStore();
        area = fixtures.createArea(store, "SALA");
        table = fixtures.createTable(store, area, "M1");
        fixtures.seat(store, table);
    }

    @Test
    void deactivatingAnOccupiedTableIsRefusedAsOccupied() throws Exception {
        mvc.perform(DiningFixtures.updateTable(store, table, area, "M1", false))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OCCUPIED"));
    }

    @Test
    void deactivatingAnAreaWithAnOccupiedTableIsRefusedAsOccupied() throws Exception {
        mvc.perform(DiningFixtures.updateArea(store, area, "SALA", false))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OCCUPIED"));
    }

    @Test
    void anOccupiedTableCanStillBeEditedWhileItStaysActive() throws Exception {
        mvc.perform(DiningFixtures.updateTable(store, table, area, "M1A", true)).andExpect(status().isOk());
    }

    @Test
    void anotherAreaOfTheStoreCanStillBeDeactivated() throws Exception {
        UUID terrace = fixtures.createArea(store, "ESPL");
        fixtures.createTable(store, terrace, "E1");

        mvc.perform(DiningFixtures.updateArea(store, terrace, "ESPL", false)).andExpect(status().isOk());
    }
}
