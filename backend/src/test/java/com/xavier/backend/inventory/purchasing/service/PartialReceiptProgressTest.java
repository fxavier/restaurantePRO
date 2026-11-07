package com.xavier.backend.inventory.purchasing.service;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.code;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static com.xavier.backend.inventory.purchasing.PurchasingSteps.buyer;
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

/**
 * D124, A46: a purchase order's receipt progress is derived from its confirmed receipts, never stored, and stays
 * apart from its lifecycle status. Partial receipts are separate receipts; over-receipt is allowed and reported.
 */
@InventoryModuleTest
class PartialReceiptProgressTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    PurchasingSteps steps;
    UUID store;
    UUID warehouse;
    UUID flour;
    UUID oil;
    UUID supplier;

    @BeforeEach
    void aLiveStoreAndASupplier() throws Exception {
        steps = new PurchasingSteps(mvc, jdbc);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        flour = fixtures.newStockItem("un");
        oil = fixtures.newStockItem("un");
        supplier = steps.newSupplier();
    }

    @Test
    void twoPartialReceiptsTakeTheOrderFromNoneToPartialToFull() throws Exception {
        UUID order = submittedOrder();
        JsonNode lines = steps.get(store, "/purchase-orders/" + order).get("lines");
        String flourLine = lineOf(lines, flour);
        String oilLine = lineOf(lines, oil);
        assertThat(progress(order)).isEqualTo("NONE");

        confirm(receipt(order, against(flourLine, flour, "4"))).andExpect(jsonPath("$.warnings").isEmpty());

        JsonNode partial = steps.get(store, "/purchase-orders/" + order);
        assertThat(partial.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(partial.get("progress").asString()).isEqualTo("PARTIAL");
        assertThat(line(partial, flour).get("received").asDouble()).isEqualTo(4.0);
        assertThat(line(partial, flour).get("outstanding").asDouble()).isEqualTo(6.0);
        assertThat(line(partial, flour).get("progress").asString()).isEqualTo("PARTIAL");
        assertThat(line(partial, oil).get("progress").asString()).isEqualTo("NONE");

        UUID draft = receipt(order, against(flourLine, flour, "6") + "," + against(oilLine, oil, "5"));
        assertThat(progress(order)).as("a draft receipt counts for nothing").isEqualTo("PARTIAL");
        confirm(draft).andExpect(jsonPath("$.warnings").isEmpty());

        JsonNode full = steps.get(store, "/purchase-orders/" + order);
        assertThat(full.get("progress").asString()).isEqualTo("FULL");
        assertThat(full.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(line(full, flour).get("outstanding").asDouble()).isZero();
    }

    @Test
    void anOverReceiptIsAllowedAndReported() throws Exception {
        UUID order = submittedOrder();
        String flourLine = lineOf(steps.get(store, "/purchase-orders/" + order).get("lines"), flour);

        confirm(receipt(order, against(flourLine, flour, "12")))
                .andExpect(jsonPath("$.warnings[0]").value("OVER_RECEIPT"));

        JsonNode view = steps.get(store, "/purchase-orders/" + order);
        assertThat(line(view, flour).get("received").asDouble()).isEqualTo(12.0);
        assertThat(line(view, flour).get("outstanding").asDouble()).isZero();
        assertThat(line(view, flour).get("progress").asString()).isEqualTo("FULL");
        assertThat(steps.balance(warehouse, flour)).startsWith("12.000000|");
    }

    @Test
    void goodsAreReceivedOnlyAgainstASubmittedOrder() throws Exception {
        UUID draftOrder = order();
        newReceipt(draftOrder, "{\"stockItemId\": \"%s\", \"packQuantity\": 1}".formatted(flour))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PO_NOT_SUBMITTED"));

        UUID order = submittedOrder();
        UUID receipt = receipt(order, "{\"stockItemId\": \"%s\", \"packQuantity\": 1}".formatted(flour));
        steps.command(store, "/purchase-orders/" + order + "/close").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        steps.command(store, "/receipts/" + receipt + "/confirm").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PO_NOT_SUBMITTED"));
        assertThat(steps.movementCount(warehouse)).isZero();
    }

    @Test
    void anOrderWithAConfirmedReceiptIsClosedNotCancelled() throws Exception {
        UUID order = submittedOrder();
        confirm(receipt(order, "{\"stockItemId\": \"%s\", \"packQuantity\": 1}".formatted(flour)));

        steps.command(store, "/purchase-orders/" + order + "/cancel").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PO_HAS_RECEIPTS"));
        steps.command(store, "/purchase-orders/" + order + "/close").andExpect(status().isOk());
        steps.command(store, "/purchase-orders/" + order + "/close").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PO_STATE"));
        assertThat(progress(order)).isEqualTo("PARTIAL");
    }

    @Test
    void anOrderLineOfAnotherItemOrOrderIsRefusedAndTheWarehouseMustBeInTheOrdersStore() throws Exception {
        UUID order = submittedOrder();
        String flourLine = lineOf(steps.get(store, "/purchase-orders/" + order).get("lines"), flour);
        newReceipt(order, against(flourLine, oil, "1")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PURCHASE_REFERENCE_MISMATCH"));
        newReceipt(submittedOrder(), against(flourLine, flour, "1")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PURCHASE_REFERENCE_MISMATCH"));

        UUID otherStore = fixtures.newActiveStore();
        UUID otherRoom = fixtures.newWarehouse(otherStore, "ALLOW");
        mvc.perform(idem(post(BO + "/receipts")).with(buyer(otherStore)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId": "%s", "purchaseOrderId": "%s",
                                 "lines": [{"stockItemId": "%s", "packQuantity": 1}]}""".formatted(otherRoom, order, flour)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_STORE_MISMATCH"));
    }

    private UUID order() throws Exception {
        return id(mvc.perform(idem(post(BO + "/purchase-orders")).with(buyer(store))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"warehouseId": "%s", "supplierId": "%s", "number": "%s",
                                 "lines": [{"stockItemId": "%s", "quantity": 10, "unitCost": 2},
                                           {"stockItemId": "%s", "quantity": 5, "unitCost": 3}]}"""
                                .formatted(warehouse, supplier, code("PO"), flour, oil)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private UUID submittedOrder() throws Exception {
        UUID order = order();
        steps.command(store, "/purchase-orders/" + order + "/submit").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        return order;
    }

    private ResultActions newReceipt(UUID order, String lines) throws Exception {
        return mvc.perform(idem(post(BO + "/receipts")).with(buyer(store)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"warehouseId\": \"%s\", \"purchaseOrderId\": \"%s\", \"lines\": [%s]}"
                        .formatted(warehouse, order, lines)));
    }

    private UUID receipt(UUID order, String lines) throws Exception {
        return id(newReceipt(order, lines).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
    }

    private ResultActions confirm(UUID receipt) throws Exception {
        return steps.command(store, "/receipts/" + receipt + "/confirm").andExpect(status().isOk());
    }

    private static String against(String orderLine, UUID item, String quantity) {
        return "{\"purchaseOrderLineId\": \"%s\", \"stockItemId\": \"%s\", \"packQuantity\": %s}"
                .formatted(orderLine, item, quantity);
    }

    private String progress(UUID order) throws Exception {
        return steps.get(store, "/purchase-orders/" + order).get("progress").asString();
    }

    private static JsonNode line(JsonNode order, UUID item) {
        for (JsonNode line : order.get("lines")) {
            if (line.get("stockItemId").asString().equals(item.toString())) {
                return line;
            }
        }
        throw new AssertionError("no line of " + item);
    }

    private static String lineOf(JsonNode lines, UUID item) {
        for (JsonNode line : lines) {
            if (line.get("stockItemId").asString().equals(item.toString())) {
                return line.get("id").asString();
            }
        }
        throw new AssertionError("no line of " + item);
    }
}
