package com.xavier.backend.inventory.controller;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.code;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Stock items and packs (D114): company-wide, so {@code INVENTORY_MANAGE} (or {@code INVENTORY_VIEW} to read) held
 * installation-wide or in any store. The base unit is one of the seeded stock units; a pack converts within the
 * base unit's dimension.
 */
@InventoryModuleTest
class StockItemHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Test
    void anInstallationWideStockManagerCreatesAnItemAndItIsAudited() throws Exception {
        String code = code("FLOUR");
        String body = create(code, "kg", manager())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.baseUnit").value("kg"))
                .andExpect(jsonPath("$.kind").value("PURCHASED"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        UUID item = id(body);

        mvc.perform(get(BO + "/stock-items/" + item).with(account(UUID.randomUUID(), Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(code));
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'STOCK_ITEM_CREATED' "
                + "AND entity_id = ?").param(item.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aTakenCodeIsAConflictAndAnUnknownUnitABusinessRule() throws Exception {
        String code = code("DUP");
        create(code, "un", manager()).andExpect(status().isCreated());

        create(code, "un", manager()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_ITEM_CODE_TAKEN"));
        create(code("X"), "lb", manager()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNKNOWN_UNIT"));
        create(" ", "un", manager()).andExpect(status().isBadRequest());
    }

    @Test
    void writingNeedsInventoryManageAndReadingInventoryView() throws Exception {
        create(code("NO"), "un", account(null, Permission.INVENTORY_VIEW)).andExpect(status().isForbidden());
        mvc.perform(get(BO + "/stock-items").with(account(null, Permission.INVENTORY_MOVE)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BO + "/stock-items/" + UUID.randomUUID()).with(account(null, Permission.INVENTORY_VIEW)))
                .andExpect(status().isNotFound());
    }

    /** Stock items are company-wide data: writing them needs the installation-wide grant (as S20, S39, S40). */
    @Test
    void aStoreScopedStockManagerReadsButNeverWritesStockItems() throws Exception {
        RequestPostProcessor storeManager = account(UUID.randomUUID(), Permission.INVENTORY_MANAGE,
                Permission.INVENTORY_VIEW);
        UUID item = fixtures.newStockItem("g");

        create(code("NO"), "un", storeManager).andExpect(status().isForbidden());
        mvc.perform(put(BO + "/stock-items/" + item).with(storeManager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"X\", \"name\": \"Rice\", \"active\": false, \"version\": 0}"))
                .andExpect(status().isForbidden());
        mvc.perform(idem(post(BO + "/stock-items/" + item + "/packs")).with(storeManager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"BAG\", \"name\": \"Pack\", \"quantity\": 1}"))
                .andExpect(status().isForbidden());

        mvc.perform(get(BO + "/stock-items/" + item).with(storeManager)).andExpect(status().isOk());
        mvc.perform(get(BO + "/stock-items/" + item + "/packs").with(storeManager)).andExpect(status().isOk());
    }

    @Test
    void anEditNeedsTheCurrentVersionAndKeepsTheBaseUnit() throws Exception {
        UUID item = fixtures.newStockItem("g");
        String renamed = code("RICE");

        update(item, renamed, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(renamed))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.baseUnit").value("g"))
                .andExpect(jsonPath("$.version").value(1));
        update(item, renamed, 0).andExpect(status().isConflict());
    }

    @Test
    void aPackConvertsItsQuantityIntoTheBaseUnit() throws Exception {
        UUID item = fixtures.newStockItem("g");

        pack(item, "SACK", "25", "kg").andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SACK"))
                .andExpect(jsonPath("$.quantityBase").value(25000.0));
        pack(item, "BAG", "500", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantityBase").value(500.0));
        pack(item, "SACK", "10", "kg").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PACK_CODE_TAKEN"));
        pack(item, "BOTTLE", "1", "l").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNIT_DIMENSION_MISMATCH"));
        pack(item, "ZERO", "0", null).andExpect(status().isBadRequest());

        mvc.perform(get(BO + "/stock-items/" + item + "/packs").with(account(null, Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].code").value("BAG"));
        pack(UUID.randomUUID(), "BAG", "1", null).andExpect(status().isNotFound());
    }

    private ResultActions create(String code, String unit, RequestPostProcessor actor)
            throws Exception {
        return mvc.perform(idem(post(BO + "/stock-items")).with(actor).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"code": "%s", "name": "Flour", "baseUnit": "%s", "kind": "PURCHASED"}""".formatted(code, unit)));
    }

    private ResultActions update(UUID item, String code, long version) throws Exception {
        return mvc.perform(put(BO + "/stock-items/" + item).with(manager()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code": "%s", "name": "Rice", "active": false, "version": %d}""".formatted(code, version)));
    }

    private ResultActions pack(UUID item, String code, String quantity, String unit) throws Exception {
        return mvc.perform(idem(post(BO + "/stock-items/" + item + "/packs")).with(manager())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code": "%s", "name": "Pack", "quantity": %s, "unit": %s}""".formatted(code, quantity,
                        unit == null ? "null" : "\"" + unit + "\"")));
    }

    private static RequestPostProcessor manager() {
        return account(null, Permission.INVENTORY_MANAGE);
    }
}
