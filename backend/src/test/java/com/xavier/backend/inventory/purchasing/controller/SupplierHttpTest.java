package com.xavier.backend.inventory.purchasing.controller;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.code;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.purchasing.PurchasingSteps;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Suppliers and their items (D123): company-wide, {@code PURCHASE_MANAGE} in any store. */
@InventoryModuleTest
class SupplierHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aSupplierIsCreatedEditedWithItsVersionAndItsCodeIsUnique() throws Exception {
        String supplierCode = code("SUP");
        RequestPostProcessor manager = account(null, Permission.PURCHASE_MANAGE);
        UUID supplier = id(create(supplierCode, manager).andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true)).andReturn().getResponse().getContentAsString());
        create(supplierCode, manager).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPLIER_CODE_TAKEN"));

        mvc.perform(put(BO + "/suppliers/" + supplier).with(manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "%s", "name": "Moagem", "taxId": "400100200", "email": "x@moagem.co.mz",
                                 "active": false, "version": 0}""".formatted(supplierCode)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.taxId").value("400100200"));
        mvc.perform(put(BO + "/suppliers/" + supplier).with(manager).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "%s", "name": "Stale", "active": true, "version": 0}""".formatted(supplierCode)))
                .andExpect(status().isConflict());
        mvc.perform(get(BO + "/suppliers/" + supplier).with(manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Moagem"));
        mvc.perform(get(BO + "/suppliers/" + UUID.randomUUID()).with(manager)).andExpect(status().isNotFound());
    }

    /** Suppliers are company-wide: writing them needs the installation-wide grant; a store's buyer reads them. */
    @Test
    void onlyAnInstallationWidePurchasingManagerWritesSuppliersAndAStoresBuyerReadsThem() throws Exception {
        RequestPostProcessor storeBuyer = account(UUID.randomUUID(), Permission.PURCHASE_MANAGE);
        UUID supplier = new PurchasingSteps(mvc, jdbc).newSupplier();
        UUID item = fixtures.newStockItem("un");

        create(code("SUP"), storeBuyer).andExpect(status().isForbidden());
        mvc.perform(put(BO + "/suppliers/" + supplier).with(storeBuyer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"X\", \"name\": \"X\", \"active\": false, \"version\": 0}"))
                .andExpect(status().isForbidden());
        addItem(supplier, item, null, null, storeBuyer).andExpect(status().isForbidden());
        create(code("SUP"), account(null, Permission.INVENTORY_MANAGE)).andExpect(status().isForbidden());

        mvc.perform(get(BO + "/suppliers").with(storeBuyer)).andExpect(status().isOk());
        mvc.perform(get(BO + "/suppliers/" + supplier).with(storeBuyer)).andExpect(status().isOk());
        mvc.perform(get(BO + "/suppliers/" + supplier + "/items").with(storeBuyer)).andExpect(status().isOk());
        mvc.perform(get(BO + "/suppliers").with(account(null, Permission.INVENTORY_VIEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSupplierSellsAnItemOncePerPackAtACostPerBaseUnit() throws Exception {
        PurchasingSteps steps = new PurchasingSteps(mvc, jdbc);
        UUID supplier = steps.newSupplier();
        UUID item = fixtures.newStockItem("un");
        UUID box = steps.newPack(item, "12");
        RequestPostProcessor manager = account(null, Permission.PURCHASE_MANAGE);

        addItem(supplier, item, box, "60", manager).andExpect(status().isCreated())
                .andExpect(jsonPath("$.lastUnitCost").value(5.0));
        addItem(supplier, item, null, null, manager).andExpect(status().isCreated())
                .andExpect(jsonPath("$.lastUnitCost").doesNotExist());
        addItem(supplier, item, box, "48", manager).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUPPLIER_ITEM_EXISTS"));
        addItem(supplier, fixtures.newStockItem("un"), box, null, manager)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PURCHASE_REFERENCE_MISMATCH"));
        mvc.perform(get(BO + "/suppliers/" + supplier + "/items").with(manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private ResultActions create(String supplierCode, RequestPostProcessor actor) throws Exception {
        return mvc.perform(idem(post(BO + "/suppliers")).with(actor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\": \"%s\", \"name\": \"Moagem\"}".formatted(supplierCode)));
    }

    private ResultActions addItem(UUID supplier, UUID item, UUID pack, String unitCost, RequestPostProcessor actor)
            throws Exception {
        return mvc.perform(idem(post(BO + "/suppliers/" + supplier + "/items")).with(actor)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"stockItemId": "%s", "packId": %s, "supplierCode": "F-1", "unitCost": %s}"""
                        .formatted(item, pack == null ? "null" : "\"" + pack + "\"", unitCost)));
    }
}
