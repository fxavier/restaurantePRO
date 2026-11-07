package com.xavier.backend.inventory.purchasing.service;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static com.xavier.backend.inventory.purchasing.PurchasingSteps.buyer;
import static com.xavier.backend.inventory.purchasing.PurchasingSteps.decimal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/** D123: each confirmed receipt sets the supplier's last cost of the item, and new lines default to it. */
@InventoryModuleTest
class LastCostUpdateTest {

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
    void eachConfirmedReceiptSetsTheLastCostButADraftDoesNot() throws Exception {
        steps.confirmedReceipt(store, warehouse, supplier, item, "10", "10");
        assertThat(lastCost()).isEqualByComparingTo("10");

        steps.receipt(store, warehouse, supplier, PurchasingSteps.line(item, "5", "99"));
        assertThat(lastCost()).isEqualByComparingTo("10");

        steps.confirmedReceipt(store, warehouse, supplier, item, "5", "12");
        assertThat(lastCost()).isEqualByComparingTo("12");
    }

    @Test
    void anOrderLineAndAReceiptLineWithoutACostTakeTheLastCost() throws Exception {
        steps.confirmedReceipt(store, warehouse, supplier, item, "10", "12");

        newOrder("{\"stockItemId\": \"%s\", \"quantity\": 3}".formatted(item)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].unitCost").value(12.0));
        steps.newReceipt(store, warehouse, supplier, "{\"stockItemId\": \"%s\", \"packQuantity\": 2}".formatted(item))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.lines[0].unitCostBase").value(12.0));
    }

    @Test
    void withoutACostOrALastCostALineIsRefused() throws Exception {
        newOrder("{\"stockItemId\": \"%s\", \"quantity\": 3}".formatted(item))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("UNIT_COST_REQUIRED"));
        steps.newReceipt(store, warehouse, supplier, "{\"stockItemId\": \"%s\", \"packQuantity\": 2}".formatted(item))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("UNIT_COST_REQUIRED"));
    }

    private ResultActions newOrder(String line) throws Exception {
        return mvc.perform(idem(post(BO + "/purchase-orders")).with(buyer(store))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"warehouseId": "%s", "supplierId": "%s", "number": "%s", "lines": [%s]}"""
                        .formatted(warehouse, supplier, InventoryFixtures.code("PO"), line)));
    }

    private java.math.BigDecimal lastCost() throws Exception {
        JsonNode items = steps.get(store, "/suppliers/" + supplier + "/items");
        assertThat(items).hasSize(1);
        return decimal(items.get(0).get("lastUnitCost"));
    }
}
