package com.xavier.backend.catalog.controller;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.CatalogFixtures.CATALOG;
import static com.xavier.backend.catalog.CatalogFixtures.code;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static com.xavier.backend.catalog.Actors.inStore;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.Actors;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.catalog.CatalogModuleTest;
import com.xavier.backend.catalog.Race;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@CatalogModuleTest
class FamilyHttpTest {

    static final String FAMILIES = CATALOG + "/families";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aCatalogManagerCreatesAFamilyAndTheWriteIsAudited() throws Exception {
        String code = code("F");

        String id = mvc.perform(post(FAMILIES).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\", \"name\": \"Drinks\"}".formatted(code)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.parentId").isEmpty())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_event
                        WHERE action = 'FAMILY_CREATED' AND entity_type = 'Family' AND entity_id = :id AND actor_id = :actor
                        """).param("id", id).param("actor", Actors.USER_ID).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void familyCodesAreUniqueAlsoWhenTwoRequestsRace() throws Exception {
        String code = code("F");
        mvc.perform(post(FAMILIES).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(family(code, null)))
                .andExpect(status().isCreated());
        mvc.perform(post(FAMILIES).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(family(code, null)))
                .andExpect(status().isConflict());

        for (int round = 0; round < 5; round++) {
            String raced = code("F");
            assertThat(Race.twice(() -> mvc.perform(post(FAMILIES).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(family(raced, null)))
                    .andReturn().getResponse().getStatus())).containsExactlyInAnyOrder(201, 409);
        }
    }

    @Test
    void aSubfamilyHangsUnderATopLevelFamilyOnly() throws Exception {
        UUID top = fixtures().family();
        UUID sub = CatalogFixtures.id(fixtures().created(post(FAMILIES).with(admin(Permission.CATALOG_MANAGE)),
                family(code("F"), top)));

        mvc.perform(get(FAMILIES + "/" + sub).with(admin(Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(top.toString()));
        mvc.perform(post(FAMILIES).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(family(code("F"), sub)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(put(FAMILIES + "/" + top).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update(code("F"), fixtures().family(), 0)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post(FAMILIES).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(family(code("F"), UUID.randomUUID())))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anUpdateWithAStaleVersionIsAConflictAndAFreshOneIsAudited() throws Exception {
        UUID family = fixtures().family();

        mvc.perform(put(FAMILIES + "/" + family).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update(code("F"), null, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(put(FAMILIES + "/" + family).with(admin(Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(update(code("F"), null, 0)))
                .andExpect(status().isConflict());

        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'FAMILY_UPDATED' AND entity_id = :id")
                .param("id", family.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void familiesAreCompanyWideSoAStoreScopedManagerIsRefused() throws Exception {
        UUID store = fixtures().store();

        mvc.perform(post(FAMILIES).with(inStore(store, Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(family(code("F"), null)))
                .andExpect(status().isForbidden());
        mvc.perform(get(FAMILIES).with(inStore(store, Permission.CATALOG_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(get(FAMILIES).with(admin(Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").isNumber());
    }

    private CatalogFixtures fixtures() {
        return new CatalogFixtures(mvc);
    }

    static String family(String code, UUID parent) {
        return """
                {"code": "%s", "name": "Drinks", "parentId": %s}""".formatted(code,
                parent == null ? "null" : "\"" + parent + "\"");
    }

    static String update(String code, UUID parent, long version) {
        return """
                {"code": "%s", "name": "Renamed", "parentId": %s, "version": %d}""".formatted(code,
                parent == null ? "null" : "\"" + parent + "\"", version);
    }
}
