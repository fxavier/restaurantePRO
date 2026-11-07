package com.xavier.backend.inventory.purchasing.service;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
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

/**
 * D125 with D113's weighted average: a supplier return is an OUT valued at the current average, like every OUT; a
 * return linked to a receipt line keeps that line's cost for reference only and never returns more than the line
 * received less what was already returned. It is a manual OUT for the negative policy (D118).
 */
@InventoryModuleTest
class SupplierReturnCostTest {

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
    UUID receiptAt10;
    String lineAt10;

    @BeforeEach
    void receiptsAt10And12MakeAnAverageOf11() throws Exception {
        steps = new PurchasingSteps(mvc, jdbc);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        item = fixtures.newStockItem("un");
        supplier = steps.newSupplier();
        receiptAt10 = steps.confirmedReceipt(store, warehouse, supplier, item, "10", "10");
        steps.confirmedReceipt(store, warehouse, supplier, item, "10", "12");
        lineAt10 = steps.get(store, "/receipts/" + receiptAt10).get("lines").get(0).get("id").asString();
        assertThat(steps.balance(warehouse, item)).isEqualTo("20.000000|220.0000|11.000000");
    }

    @Test
    void aLinkedReturnIsValuedAtTheAverageAndKeepsItsReceiptCostForReference() throws Exception {
        UUID returned = linkedReturn("5");

        confirm(returned).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(returnMovement(returned)).isEqualTo("SUPPLIER_RETURN|-5.000000|11.000000|-55.0000|10.000000");
        assertThat(steps.balance(warehouse, item)).isEqualTo("15.000000|165.0000|11.000000");
    }

    @Test
    void anUnlinkedReturnIsValuedAtTheAverage() throws Exception {
        UUID returned = id(newReturn(warehouse, null, "{\"stockItemId\": \"%s\", \"quantity\": 3}".formatted(item))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        confirm(returned).andExpect(status().isOk());

        assertThat(returnMovement(returned)).isEqualTo("SUPPLIER_RETURN|-3.000000|11.000000|-33.0000|");
    }

    @Test
    void aReceiptLineIsNeverReturnedBeyondWhatItReceived() throws Exception {
        confirm(linkedReturn("6")).andExpect(status().isOk());
        UUID tooMuch = linkedReturn("5");

        confirm(tooMuch).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RETURN_EXCEEDS_RECEIVED"))
                .andExpect(jsonPath("$.available").value(4.0));
        assertThat(steps.movements(SupplierReturnService.SOURCE_TYPE, tooMuch)).isEmpty();
        assertThat(steps.get(store, "/supplier-returns/" + tooMuch).get("status").asString()).isEqualTo("DRAFT");

        confirm(linkedReturn("4")).andExpect(status().isOk());
        assertThat(steps.balance(warehouse, item)).startsWith("10.000000|");
    }

    @Test
    void aRefusingWarehouseRefusesAReturnBelowZero() throws Exception {
        UUID strict = fixtures.newWarehouse(store, "REFUSE_MANUAL");
        steps.confirmedReceipt(store, strict, supplier, item, "2", "5");
        UUID returned = id(newReturn(strict, null, "{\"stockItemId\": \"%s\", \"quantity\": 3}".formatted(item))
                .andReturn().getResponse().getContentAsString());

        confirm(returned).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NEGATIVE_STOCK_REFUSED"));

        assertThat(steps.get(store, "/supplier-returns/" + returned).get("status").asString()).isEqualTo("DRAFT");
        assertThat(steps.balance(strict, item)).startsWith("2.000000|");
    }

    @Test
    void aReturnIsConfirmedOnceAndOnlyAgainstAConfirmedReceipt() throws Exception {
        UUID returned = linkedReturn("1");
        confirm(returned).andExpect(status().isOk());
        confirm(returned).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RETURN_STATE"));
        assertThat(steps.movements(SupplierReturnService.SOURCE_TYPE, returned)).hasSize(1);

        UUID draftReceipt = steps.receipt(store, warehouse, supplier, PurchasingSteps.line(item, "1", "1"));
        newReturn(warehouse, draftReceipt, "{\"stockItemId\": \"%s\", \"quantity\": 1}".formatted(item))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RECEIPT_NOT_CONFIRMED"));
    }

    @Test
    void aDraftReturnIsEditedWithItsVersion() throws Exception {
        UUID returned = linkedReturn("1");
        long version = steps.get(store, "/supplier-returns/" + returned).get("version").asLong();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(BO + "/supplier-returns/"
                                + returned).with(buyer(store))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf()).contentType(MediaType.APPLICATION_JSON).content("""
                                {"reason": "wrong size", "version": %d,
                                 "lines": [{"receiptLineId": "%s", "stockItemId": "%s", "quantity": 2}]}"""
                                .formatted(version, lineAt10, item)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].quantityBase").value(2.0))
                .andExpect(jsonPath("$.reason").value("wrong size"));
        confirm(returned).andExpect(status().isOk());

        assertThat(returnMovement(returned)).isEqualTo("SUPPLIER_RETURN|-2.000000|11.000000|-22.0000|10.000000");
    }

    private UUID linkedReturn(String quantity) throws Exception {
        return id(newReturn(warehouse, receiptAt10, """
                {"receiptLineId": "%s", "stockItemId": "%s", "quantity": %s}""".formatted(lineAt10, item, quantity))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions newReturn(UUID from, UUID receipt, String line) throws Exception {
        String link = receipt == null ? "\"supplierId\": \"" + supplier + "\""
                : "\"goodsReceiptId\": \"" + receipt + "\"";
        return mvc.perform(idem(post(BO + "/supplier-returns")).with(buyer(store))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"warehouseId": "%s", %s, "reason": "damaged", "lines": [%s]}""".formatted(from, link, line)));
    }

    private ResultActions confirm(UUID returned) throws Exception {
        return steps.command(store, "/supplier-returns/" + returned + "/confirm");
    }

    private String returnMovement(UUID returned) {
        return jdbc.sql("""
                        SELECT type || '|' || quantity || '|' || unit_cost || '|' || value || '|'
                               || coalesce(reference_unit_cost::text, '')
                        FROM inventory.stock_movement WHERE source_type = 'SUPPLIER_RETURN' AND source_id = ?""")
                .param(returned).query(String.class).single();
    }
}
