package com.xavier.backend.catalog;

import static com.xavier.backend.catalog.Actors.admin;
import static com.xavier.backend.catalog.Actors.inStore;
import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * Exclusions are configuration of one store (D146): read with {@code CATALOG_VIEW} and written with
 * {@code CATALOG_MANAGE} in that store, or installation-wide; another store's grant is refused.
 */
@CatalogModuleTest
class ExclusionPermissionTest {

    @Autowired
    MockMvc mvc;

    @Test
    void anotherStoresGrantIsRefusedAndTheStoresOwnGrantsDecide() throws Exception {
        CatalogFixtures fixtures = new CatalogFixtures(mvc);
        UUID store = fixtures.store();
        UUID other = fixtures.store();
        JsonNode item = fixtures.item();
        String path = Exclusions.path(store) + "/" + id(item);

        mvc.perform(put(path).with(inStore(other, Permission.CATALOG_MANAGE)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).with(inStore(store, Permission.CATALOG_VIEW)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(put(path).with(inStore(store, Permission.CATALOG_MANAGE)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.itemId").value(id(item).toString()))
                .andExpect(jsonPath("$.itemCode").value(item.get("code").asString()))
                .andExpect(jsonPath("$.excludedByAccountId").value(Actors.USER_ID.toString()));

        mvc.perform(get(Exclusions.path(store)).with(inStore(other, Permission.CATALOG_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(Exclusions.path(store)).with(inStore(store, Permission.CATALOG_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].itemId").value(id(item).toString()))
                .andExpect(jsonPath("$.content[0].itemName").value(item.get("name").asString()));

        mvc.perform(delete(path).with(inStore(other, Permission.CATALOG_MANAGE)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path).with(inStore(store, Permission.CATALOG_VIEW)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(delete(path).with(admin(Permission.CATALOG_MANAGE)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(delete(path).with(admin(Permission.CATALOG_MANAGE)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownStoreOrItemIsACoded404() throws Exception {
        CatalogFixtures fixtures = new CatalogFixtures(mvc);
        UUID store = fixtures.store();
        UUID item = id(fixtures.item());
        UUID unknown = UUID.randomUUID();

        mvc.perform(put(Exclusions.path(unknown) + "/" + item).with(admin(Permission.CATALOG_MANAGE)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        mvc.perform(put(Exclusions.path(store) + "/" + unknown).with(admin(Permission.CATALOG_MANAGE)).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));
    }
}
