package com.xavier.backend.inventory.purchasing.service;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.code;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static com.xavier.backend.inventory.purchasing.PurchasingSteps.buyer;
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

/**
 * Quantities and values from requests are bounded to what the ledger columns hold, as coded 422s and never a numeric
 * overflow: orders, cost corrections and returns (receipts: {@code PackConversionTest}).
 */
@InventoryModuleTest
class PurchaseBoundsTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    PurchasingSteps steps;
    UUID store;
    UUID warehouse;
    UUID supplier;

    @BeforeEach
    void aLiveStore() throws Exception {
        steps = new PurchasingSteps(mvc, jdbc);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        supplier = steps.newSupplier();
    }

    @Test
    void anOrderLineTooLargeInQuantityOrValueIsRefused() throws Exception {
        UUID item = fixtures.newStockItem("un");
        UUID box = steps.newPack(item, "12");

        order("{\"stockItemId\": \"%s\", \"quantity\": 9999999999999, \"unitCost\": 9999999999999}"
                .formatted(item)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LINE_VALUE_TOO_LARGE"));
        order("{\"stockItemId\": \"%s\", \"packId\": \"%s\", \"quantity\": 9999999999999, \"unitCost\": 1}"
                .formatted(item, box)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
    }

    @Test
    void aCorrectionWhoseValueWouldNotFitIsRefused() throws Exception {
        UUID item = fixtures.newStockItem("un");
        UUID receipt = steps.confirmedReceipt(store, warehouse, supplier, item, "1000", "1");
        String line = steps.get(store, "/receipts/" + receipt).get("lines").get(0).get("id").asString();

        mvc.perform(idem(post(BO + "/receipts/" + receipt + "/lines/" + line + "/cost-corrections"))
                        .with(buyer(store)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newUnitCost\": 9999999999999, \"reason\": \"invoice\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LINE_VALUE_TOO_LARGE"));
    }

    @Test
    void aReturnQuantityThatConvertsToNothingIsRefused() throws Exception {
        UUID item = fixtures.newStockItem("kg");

        mvc.perform(idem(post(BO + "/supplier-returns")).with(buyer(store)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\": \"%s\", \"supplierId\": \"%s\", \"lines\": [{\"stockItemId\": \"%s\", \"quantity\": 0.000001, \"unit\": \"g\"}]}"
                                .formatted(warehouse, supplier, item)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
    }

    private ResultActions order(String line) throws Exception {
        return mvc.perform(idem(post(BO + "/purchase-orders")).with(buyer(store))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"warehouseId\": \"%s\", \"supplierId\": \"%s\", \"number\": \"%s\", \"lines\": [%s]}"
                        .formatted(warehouse, supplier, code("PO"), line)));
    }
}
