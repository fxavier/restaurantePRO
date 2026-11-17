package com.xavier.backend.catalog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.Actors.pos;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.catalog.CatalogModuleTest;
import com.xavier.backend.catalog.Publications;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** D39: the POS reads its catalog for the session's store; {@code ?storeId} is optional and must match it. */
@CatalogModuleTest
class PosCatalogHttpTest {

    static final String POS_CATALOG = "/api/v1/pos/catalog";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    tools.jackson.databind.json.JsonMapper json;

    /** The response as it was before S77 (F6): the stored snapshot parsed into a tree, then written by the mapper. */
    record TreeResponse(UUID releaseId, UUID storeId, long version, java.time.Instant effectiveAt,
                        tools.jackson.databind.JsonNode catalog) {
    }

    /** S77 (F6): the wire bytes of the POS catalog do not change, whatever the server does with the snapshot. */
    @Test
    void theCatalogsWireJsonIsTheStoredSnapshotWrittenAsATree() throws Exception {
        UUID store = publications().sellableStore().store();
        UUID release = publications().publishNew(store);
        java.util.Map<String, Object> row = com.xavier.backend.OwnerDatabase.shared().jdbc().sql("""
                        SELECT catalog_version, effective_at, snapshot::text AS snapshot
                        FROM catalog.configuration_release WHERE id = ?""").param(release).query().singleRow();
        String expected = json.writeValueAsString(new TreeResponse(release, store,
                ((Number) row.get("catalog_version")).longValue(),
                ((java.sql.Timestamp) row.get("effective_at")).toInstant(),
                json.readTree((String) row.get("snapshot"))));

        String body = mvc.perform(get(POS_CATALOG).with(pos(store, Permission.CATALOG_VIEW)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(body).isEqualTo(expected);
    }

    @Test
    void aPosSessionReadsItsStoresCatalogWithoutNamingTheStore() throws Exception {
        UUID store = publications().sellableStore().store();
        UUID release = publications().publishNew(store);

        mvc.perform(get(POS_CATALOG).with(pos(store, Permission.CATALOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.releaseId").value(release.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.effectiveAt").isNotEmpty())
                .andExpect(jsonPath("$.catalog.releaseId").value(release.toString()));
    }

    @Test
    void theSessionsOwnStoreMayBeNamedButAnotherStoreIsForbidden() throws Exception {
        UUID store = publications().sellableStore().store();
        publications().publishNew(store);
        UUID other = new CatalogFixtures(mvc).store();

        mvc.perform(get(POS_CATALOG).param("storeId", store.toString()).with(pos(store, Permission.CATALOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(store.toString()));
        mvc.perform(get(POS_CATALOG).param("storeId", other.toString()).with(pos(store, Permission.CATALOG_VIEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    void beforeTheFirstPublicationThereIsNothingToSell() throws Exception {
        UUID store = new CatalogFixtures(mvc).store();

        mvc.perform(get(POS_CATALOG).with(pos(store, Permission.CATALOG_VIEW)))
                .andExpect(status().isNotFound());
    }

    @Test
    void aBackOfficeSessionOrAnEmployeeWithoutCatalogViewIsForbidden() throws Exception {
        UUID store = publications().sellableStore().store();
        publications().publishNew(store);

        mvc.perform(get(POS_CATALOG).param("storeId", store.toString()).with(inStore(store, Permission.CATALOG_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(POS_CATALOG).with(pos(store, Permission.DINING_VIEW)))
                .andExpect(status().isForbidden());
    }

    private Publications publications() {
        return new Publications(mvc, jdbc);
    }
}
