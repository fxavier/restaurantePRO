package com.xavier.backend.dining.controller;

import static com.xavier.backend.dining.DiningActors.inStore;
import static com.xavier.backend.dining.DiningActors.pos;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.dining.DiningModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /api/v1/pos/tables}: DINING_VIEW, the store from the POS session (D39), statuses from statusOf. */
@DiningModuleTest
class PosTableMapHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    DiningFixtures fixtures;

    final UUID waiter = UUID.randomUUID();
    UUID store;

    @BeforeEach
    void newStore() throws Exception {
        store = fixtures.newStore();
    }

    @Test
    void thePosMapShowsTheSessionStoresActiveAreasWithTheirActiveTablesAllFree() throws Exception {
        UUID sala = fixtures.createArea(store, "SALA");
        UUID bar = fixtures.createArea(store, "BAR");
        fixtures.createTable(store, sala, "M2");
        fixtures.createTable(store, sala, "M1");
        UUID retired = fixtures.createTable(store, sala, "M9");
        fixtures.deactivateTable(store, retired, sala, "M9");
        UUID closedArea = fixtures.createArea(store, "ESPL");
        fixtures.createTable(store, closedArea, "E1");
        fixtures.deactivateArea(store, closedArea, "ESPL");
        UUID otherStore = fixtures.newStore();
        fixtures.createTable(otherStore, fixtures.createArea(otherStore, "SALA"), "X1");

        mvc.perform(get(DiningFixtures.POS_TABLES).with(pos(store, waiter, Permission.DINING_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.areas[*].code", contains("BAR", "SALA")))
                .andExpect(jsonPath("$.areas[0].id").value(bar.toString()))
                .andExpect(jsonPath("$.areas[0].tables.length()").value(0))
                .andExpect(jsonPath("$.areas[1].tables[*].code", contains("M1", "M2")))
                .andExpect(jsonPath("$.areas[1].tables[0].seats").value(4))
                .andExpect(jsonPath("$.areas[1].tables[*].status", everyItem(is("FREE"))))
                .andExpect(jsonPath("$.areas[1].tables[*].locked", everyItem(is(false))))
                .andExpect(jsonPath("$.areas[1].tables[0].openedAt", nullValue()))
                .andExpect(jsonPath("$.areas[1].tables[0].guests", nullValue()));
    }

    @Test
    void anOccupiedTableShowsWhenWhoAndHowManyAndTheIndicatorsAreIndependent() throws Exception {
        UUID sala = fixtures.createArea(store, "SALA");
        UUID m1 = fixtures.createTable(store, sala, "M1");
        UUID m2 = fixtures.createTable(store, sala, "M2");
        UUID order = fixtures.seat(store, m1, waiter, 5);
        fixtures.markSubtotalPrinted(store, order);
        mvc.perform(post(DiningFixtures.POS_TABLES + "/" + m2 + "/lock").with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(pos(store, waiter, Permission.TABLE_LOCK))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"x\"}")).andExpect(status().isOk());

        mvc.perform(get(DiningFixtures.POS_TABLES).with(pos(store, waiter, Permission.DINING_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areas[0].tables[0].status").value("OCCUPIED"))
                .andExpect(jsonPath("$.areas[0].tables[0].guests").value(5))
                .andExpect(jsonPath("$.areas[0].tables[0].openedByEmployeeId").value(waiter.toString()))
                .andExpect(jsonPath("$.areas[0].tables[0].openedAt", notNullValue()))
                .andExpect(jsonPath("$.areas[0].tables[0].subtotalPrinted").value(true))
                .andExpect(jsonPath("$.areas[0].tables[0].locked").value(false))
                .andExpect(jsonPath("$.areas[0].tables[1].status").value("FREE"))
                .andExpect(jsonPath("$.areas[0].tables[1].locked").value(true))
                .andExpect(jsonPath("$.areas[0].tables[1].subtotalPrinted").value(false));
    }

    @Test
    void aStoreIdMayBeGivenButOnlyTheSessionsOwn() throws Exception {
        UUID otherStore = fixtures.newStore();

        mvc.perform(get(DiningFixtures.POS_TABLES).param("storeId", store.toString())
                .with(pos(store, waiter, Permission.DINING_VIEW))).andExpect(status().isOk());
        mvc.perform(get(DiningFixtures.POS_TABLES).param("storeId", otherStore.toString())
                .with(pos(store, waiter, Permission.DINING_VIEW))).andExpect(status().isForbidden());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void askingForAnotherStoresMapIsLoggedAsADenial(CapturedOutput output) throws Exception {
        UUID otherStore = fixtures.newStore();

        mvc.perform(get(DiningFixtures.POS_TABLES).param("storeId", otherStore.toString())
                .with(pos(store, waiter, Permission.DINING_VIEW))).andExpect(status().isForbidden());

        assertThat(output.getOut()).contains("Access denied").contains(otherStore.toString())
                .contains(waiter.toString());
    }

    @Test
    void thePosMapNeedsDiningViewInAPosSession() throws Exception {
        mvc.perform(get(DiningFixtures.POS_TABLES).with(pos(store, waiter, Permission.DINING_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(get(DiningFixtures.POS_TABLES).with(inStore(store, Permission.DINING_VIEW)))
                .andExpect(status().isForbidden());
    }
}
