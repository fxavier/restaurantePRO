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
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@DiningModuleTest
class RoomAreaHttpTest {

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
    void aStoreManagerCreatesAnAreaAndItIsAudited() throws Exception {
        JsonNode area = DiningFixtures.JSON.readTree(mvc.perform(post(DiningFixtures.AREAS)
                        .with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DiningFixtures.newArea(store, "SALA")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.code").value("SALA"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString());

        assertThat(fixtures.audits("ROOM_AREA_CREATED", area.get("id").asString(), store)).isEqualTo(1);
    }
    @Test
    void anUpdateWithTheCurrentVersionRenamesAndDeactivatesTheAreaAndIsAudited() throws Exception {
        UUID area = fixtures.createArea(store, "SALA");

        mvc.perform(put(DiningFixtures.AREAS + "/" + area).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update("TERRACO", false, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("TERRACO"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(fixtures.audits("ROOM_AREA_UPDATED", area.toString(), store)).isEqualTo(1);
    }

    @Test
    void anUpdateWithAStaleVersionIsAConflictAndChangesNothing() throws Exception {
        UUID area = fixtures.createArea(store, "SALA");
        mvc.perform(put(DiningFixtures.AREAS + "/" + area).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(update("SALA1", true, 0))).andExpect(status().isOk());

        mvc.perform(put(DiningFixtures.AREAS + "/" + area).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update("SALA2", true, 0)))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));

        mvc.perform(get(DiningFixtures.AREAS + "/" + area).with(inStore(store, Permission.DINING_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SALA1"));
        assertThat(fixtures.audits("ROOM_AREA_UPDATED", area.toString(), store)).isEqualTo(1);
    }

    @Test
    void anotherStoresManagerCannotSeeOrChangeThisStoresAreas() throws Exception {
        UUID area = fixtures.createArea(store, "SALA");
        UUID otherStore = fixtures.newStore();

        mvc.perform(post(DiningFixtures.AREAS).with(inStore(otherStore, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DiningFixtures.newArea(store, "BAR")))
                .andExpect(status().isForbidden());
        mvc.perform(get(DiningFixtures.AREAS).param("storeId", store.toString())
                .with(inStore(otherStore, Permission.DINING_MANAGE))).andExpect(status().isForbidden());
        mvc.perform(get(DiningFixtures.AREAS + "/" + area).with(inStore(otherStore, Permission.DINING_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(put(DiningFixtures.AREAS + "/" + area).with(inStore(otherStore, Permission.DINING_MANAGE))
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(update("X", true, 0)))
                .andExpect(status().isForbidden());
    }

    @Test
    void managingAreasNeedsDiningManage() throws Exception {
        UUID area = fixtures.createArea(store, "SALA");

        mvc.perform(get(DiningFixtures.AREAS + "/" + area).with(inStore(store, Permission.DINING_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(post(DiningFixtures.AREAS).with(inStore(store, Permission.DINING_VIEW)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DiningFixtures.newArea(store, "BAR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void areasAreListedPerStorePagedByCode() throws Exception {
        fixtures.createArea(store, "B");
        fixtures.createArea(store, "A");
        fixtures.createArea(fixtures.newStore(), "C");

        mvc.perform(get(DiningFixtures.AREAS).param("storeId", store.toString()).param("size", "1")
                        .with(inStore(store, Permission.DINING_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].code").value("A"));
    }

    @Test
    void areaCodesAreUniqueWithinAStoreButNotAcrossStores() throws Exception {
        fixtures.createArea(store, "SALA");

        mvc.perform(post(DiningFixtures.AREAS).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DiningFixtures.newArea(store, "SALA")))
                .andExpect(status().isConflict());
        fixtures.createArea(fixtures.newStore(), "SALA");
    }

    @Test
    void renamingAnAreaToACodeInUseIsAConflict() throws Exception {
        fixtures.createArea(store, "SALA");
        UUID bar = fixtures.createArea(store, "BAR");

        mvc.perform(put(DiningFixtures.AREAS + "/" + bar).with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update("SALA", true, 0)))
                .andExpect(status().isConflict());
    }

    @Test
    void concurrentCreatesWithTheSameCodeGiveOneAreaAndOneConflict() throws Exception {
        for (int round = 0; round < 10; round++) {
            String code = "R" + round;
            List<Integer> statuses = DiningFixtures.race(() -> mvc.perform(post(DiningFixtures.AREAS)
                            .with(inStore(store, Permission.DINING_MANAGE)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(DiningFixtures.newArea(store, code)))
                    .andReturn().getResponse().getStatus());

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(201, 409);
        }
    }

    @Test
    void anAreaNeedsAnExistingStore() throws Exception {
        mvc.perform(post(DiningFixtures.AREAS).with(admin(Permission.DINING_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DiningFixtures.newArea(UUID.randomUUID(), "SALA")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownAreaIsNotFound() throws Exception {
        mvc.perform(get(DiningFixtures.AREAS + "/" + UUID.randomUUID()).with(admin(Permission.DINING_MANAGE)))
                .andExpect(status().isNotFound());
    }

    static String update(String code, boolean active, long version) {
        return """
                {"code": "%s", "name": "Renamed", "active": %s, "version": %d}""".formatted(code, active, version);
    }
}
