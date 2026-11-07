package com.xavier.backend.inventory.purchasing.service;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.code;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static com.xavier.backend.inventory.purchasing.PurchasingSteps.buyer;
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
import com.xavier.backend.inventory.purchasing.PurchasingSteps;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** D124, A46: purchase orders alone never change stock, whatever their lifecycle; only receipts do (§8.4). */
@InventoryModuleTest
class PurchaseOrderNeverMovesStockTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    PurchasingSteps steps;
    UUID store;
    UUID warehouse;
    UUID item;
    UUID supplier;

    @BeforeEach
    void aLiveStoreAndASupplier() throws Exception {
        steps = new PurchasingSteps(mvc, jdbc);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        item = fixtures.newStockItem("un");
        supplier = steps.newSupplier();
    }

    @Test
    void creatingEditingSubmittingClosingAndCancellingOrdersPostsNothing() throws Exception {
        UUID closed = order("10");
        long version = steps.get(store, "/purchase-orders/" + closed).get("version").asLong();
        mvc.perform(put(BO + "/purchase-orders/" + closed).with(buyer(store)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"lines": [{"stockItemId": "%s", "quantity": 20, "unitCost": 1}], "version": %d}"""
                                .formatted(item, version)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].quantity").value(20.0));
        steps.command(store, "/purchase-orders/" + closed + "/submit").andExpect(status().isOk());
        steps.command(store, "/purchase-orders/" + closed + "/close").andExpect(status().isOk());
        UUID cancelledDraft = order("3");
        steps.command(store, "/purchase-orders/" + cancelledDraft + "/cancel").andExpect(status().isOk());
        UUID cancelledSubmitted = order("4");
        steps.command(store, "/purchase-orders/" + cancelledSubmitted + "/submit").andExpect(status().isOk());
        steps.command(store, "/purchase-orders/" + cancelledSubmitted + "/cancel").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(steps.movementCount(warehouse)).isZero();
        assertThat(steps.balance(warehouse, item)).isNull();
    }

    @Test
    void aSubmittedOrderIsNoLongerEditedAndAStaleEditIsAConflict() throws Exception {
        UUID order = order("10");
        long version = steps.get(store, "/purchase-orders/" + order).get("version").asLong();
        steps.command(store, "/purchase-orders/" + order + "/submit").andExpect(status().isOk());

        mvc.perform(put(BO + "/purchase-orders/" + order).with(buyer(store)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"lines": [{"stockItemId": "%s", "quantity": 1, "unitCost": 1}], "version": %d}"""
                                .formatted(item, version)))
                .andExpect(status().isConflict());
        long current = steps.get(store, "/purchase-orders/" + order).get("version").asLong();
        mvc.perform(put(BO + "/purchase-orders/" + order).with(buyer(store)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"lines": [{"stockItemId": "%s", "quantity": 1, "unitCost": 1}], "version": %d}"""
                                .formatted(item, current)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PO_STATE"));
        steps.command(store, "/purchase-orders/" + order + "/submit").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PO_STATE"));
    }

    @Test
    void anOrderNumberIsUniqueInItsStoreAndOrdersAreScopedToTheirStore() throws Exception {
        String number = code("PO");
        create(number).andExpect(status().isCreated());
        create(number).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PO_NUMBER_TAKEN"));

        UUID order = id(create(code("PO")).andReturn().getResponse().getContentAsString());
        UUID elsewhere = UUID.randomUUID();
        mvc.perform(get(BO + "/purchase-orders/" + order).with(InventoryFixtures.account(elsewhere,
                Permission.INVENTORY_VIEW))).andExpect(status().isForbidden());
        mvc.perform(idem(post(BO + "/purchase-orders/" + order + "/submit"))
                        .with(InventoryFixtures.account(store, Permission.INVENTORY_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BO + "/purchase-orders/" + UUID.randomUUID()).with(buyer(store)))
                .andExpect(status().isNotFound());
        mvc.perform(get(BO + "/purchase-orders").param("storeId", store.toString()).with(buyer(store)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].storeId").value(store.toString()));
    }

    private UUID order(String quantity) throws Exception {
        return id(mvc.perform(idem(post(BO + "/purchase-orders")).with(buyer(store))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"warehouseId": "%s", "supplierId": "%s", "number": "%s",
                                 "lines": [{"stockItemId": "%s", "quantity": %s, "unitCost": 2}]}"""
                                .formatted(warehouse, supplier, code("PO"), item, quantity)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions create(String number) throws Exception {
        return mvc.perform(idem(post(BO + "/purchase-orders")).with(buyer(store))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"warehouseId": "%s", "supplierId": "%s", "number": "%s",
                         "lines": [{"stockItemId": "%s", "quantity": 1, "unitCost": 2}]}"""
                        .formatted(warehouse, supplier, number, item)));
    }
}
