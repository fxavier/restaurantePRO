package com.xavier.backend.inventory.purchasing.service;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static com.xavier.backend.inventory.purchasing.PurchasingSteps.buyer;
import static com.xavier.backend.inventory.purchasing.PurchasingSteps.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.purchasing.PurchasingSteps;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * D131 "purchases by supplier and item", net: what a store's confirmed receipts brought in over a period of business
 * days, less its confirmed supplier returns, plus the cost corrections dated in it. Drafts and other stores never count.
 */
@InventoryModuleTest
class PurchaseReportTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    PurchasingSteps steps;
    UUID store;
    UUID flour;
    UUID oil;
    UUID supplierA;
    UUID supplierB;
    LocalDate day;

    @BeforeEach
    void twoSuppliersAndACorrection() throws Exception {
        steps = new PurchasingSteps(mvc, jdbc);
        store = fixtures.newActiveStore();
        UUID warehouse = fixtures.newWarehouse(store, "ALLOW");
        flour = fixtures.newStockItem("un");
        oil = fixtures.newStockItem("un");
        supplierA = steps.newSupplier();
        supplierB = steps.newSupplier();
        UUID receiptA = steps.receipt(store, warehouse, supplierA, line(flour, "10", "2") + "," + line(oil, "5", "3"));
        steps.command(store, "/receipts/" + receiptA + "/confirm").andExpect(status().isOk());
        steps.confirmedReceipt(store, warehouse, supplierB, flour, "4", "2.5");
        steps.receipt(store, warehouse, supplierB, line(oil, "100", "100"));
        UUID elsewhere = fixtures.newActiveStore();
        steps.confirmedReceipt(elsewhere, fixtures.newWarehouse(elsewhere, "ALLOW"), supplierA, flour, "7", "7");

        UUID returned = InventoryFixtures.id(mvc.perform(idem(post(BO + "/supplier-returns")).with(buyer(store))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\": \"%s\", \"supplierId\": \"%s\", \"lines\": [{\"stockItemId\": \"%s\", \"quantity\": 1}]}"
                                .formatted(warehouse, supplierB, oil)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        steps.command(store, "/supplier-returns/" + returned + "/confirm").andExpect(status().isOk());

        JsonNode receipt = steps.get(store, "/receipts/" + receiptA);
        day = LocalDate.parse(receipt.get("businessDate").asString());
        String flourLine = receipt.get("lines").get(0).get("stockItemId").asString().equals(flour.toString())
                ? receipt.get("lines").get(0).get("id").asString() : receipt.get("lines").get(1).get("id").asString();
        mvc.perform(idem(post(BO + "/receipts/" + receiptA + "/lines/" + flourLine + "/cost-corrections"))
                        .with(buyer(store)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newUnitCost\": 2.5, \"reason\": \"invoice\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void bySupplierSumsWhatEachSupplierDeliveredAndItsCorrections() throws Exception {
        JsonNode report = json(report("supplier", account(Permission.INVENTORY_VIEW)).andExpect(status().isOk()));

        assertThat(report.get("rows")).hasSize(2);
        JsonNode a = row(report, supplierA);
        assertThat(a.get("receivedQuantity").isNull()).isTrue();
        assertThat(a.get("receivedValue").asDouble()).isEqualTo(35.0);
        assertThat(a.get("correctionValue").asDouble()).isEqualTo(5.0);
        assertThat(a.get("returnedValue").asDouble()).isEqualTo(0.0);
        assertThat(a.get("netValue").asDouble()).isEqualTo(40.0);
        JsonNode b = row(report, supplierB);
        assertThat(b.get("receivedValue").asDouble()).isEqualTo(10.0);
        assertThat(b.get("returnedValue").asDouble()).as("1 oil at the average 3").isEqualTo(3.0);
        assertThat(b.get("netValue").asDouble()).isEqualTo(7.0);
        assertThat(report.get("totalValue").asDouble()).isEqualTo(47.0);
    }

    @Test
    void byItemSumsQuantitiesAndValuesPerStockItem() throws Exception {
        JsonNode report = json(report("item", account(Permission.INVENTORY_VIEW)).andExpect(status().isOk()));

        JsonNode flourRow = row(report, flour);
        assertThat(flourRow.get("receivedQuantity").asDouble()).isEqualTo(14.0);
        assertThat(flourRow.get("receivedValue").asDouble()).isEqualTo(30.0);
        assertThat(flourRow.get("correctionValue").asDouble()).isEqualTo(5.0);
        JsonNode oilRow = row(report, oil);
        assertThat(oilRow.get("receivedQuantity").asDouble()).isEqualTo(5.0);
        assertThat(oilRow.get("returnedQuantity").asDouble()).isEqualTo(1.0);
        assertThat(oilRow.get("netQuantity").asDouble()).isEqualTo(4.0);
        assertThat(oilRow.get("netValue").asDouble()).isEqualTo(12.0);
        assertThat(report.get("totalValue").asDouble()).isEqualTo(47.0);
    }

    @Test
    void anotherPeriodIsEmptyAndTheReportIsScopedAndValidated() throws Exception {
        mvc.perform(get(BO + "/reports/purchases").param("storeId", store.toString())
                        .param("from", day.plusDays(1).toString()).param("to", day.plusDays(2).toString())
                        .param("groupBy", "item").with(account(Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows").isEmpty());
        report("supplier", InventoryFixtures.account(UUID.randomUUID(), Permission.INVENTORY_VIEW))
                .andExpect(status().isForbidden());
        report("warehouse", account(Permission.INVENTORY_VIEW)).andExpect(status().isBadRequest());
        mvc.perform(get(BO + "/reports/purchases").param("storeId", store.toString())
                        .param("from", day.toString()).param("to", day.minusDays(1).toString())
                        .param("groupBy", "item").with(account(Permission.INVENTORY_VIEW)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
    }

    private ResultActions report(String groupBy, RequestPostProcessor actor) throws Exception {
        return mvc.perform(get(BO + "/reports/purchases").param("storeId", store.toString())
                .param("from", day.toString()).param("to", day.toString()).param("groupBy", groupBy).with(actor));
    }

    private RequestPostProcessor account(Permission permission) {
        return InventoryFixtures.account(store, permission);
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return InventoryFixtures.json(result.andReturn().getResponse().getContentAsString());
    }

    private static JsonNode row(JsonNode report, UUID id) {
        for (JsonNode row : report.get("rows")) {
            if (row.get("id").asString().equals(id.toString())) {
                return row;
            }
        }
        throw new AssertionError("no row for " + id);
    }
}
