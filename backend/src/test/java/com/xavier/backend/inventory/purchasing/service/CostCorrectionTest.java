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
import com.xavier.backend.inventory.model.event.StockMovementsPosted;
import com.xavier.backend.inventory.purchasing.PurchasingSteps;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * D125, A46: a later price difference never edits the receipt. It revalues only the share of the line still on hand
 * ({@code min(received, on hand) × Δ}, a REVALUATION) and records the consumed rest as a cost variance; reporting is
 * told through {@code StockMovementsPosted(COST_CORRECTION, …)} either way.
 */
@InventoryModuleTest
@RecordApplicationEvents
class CostCorrectionTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ApplicationEvents events;

    PurchasingSteps steps;
    UUID store;
    UUID warehouse;
    UUID item;
    UUID supplier;
    UUID receipt;
    String line;

    @BeforeEach
    void tenReceivedAt10ThenFourConsumed() throws Exception {
        steps = new PurchasingSteps(mvc, jdbc);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        item = fixtures.newStockItem("un");
        supplier = steps.newSupplier();
        receipt = steps.confirmedReceipt(store, warehouse, supplier, item, "10", "10");
        line = steps.get(store, "/receipts/" + receipt).get("lines").get(0).get("id").asString();
        steps.waste(store, warehouse, item, "4");
        assertThat(steps.balance(warehouse, item)).isEqualTo("6.000000|60.0000|10.000000");
    }

    @Test
    void onlyTheShareStillOnHandIsRevaluedAndTheConsumedRestIsAVariance() throws Exception {
        JsonNode correction = created(correct("12", "invoice INV-7 at 12"));

        assertThat(correction.get("previousUnitCost").asDouble()).isEqualTo(10.0);
        assertThat(correction.get("revaluedQuantity").asDouble()).isEqualTo(6.0);
        assertThat(correction.get("revaluationValue").asDouble()).isEqualTo(12.0);
        assertThat(correction.get("varianceValue").asDouble()).isEqualTo(8.0);
        UUID correctionId = UUID.fromString(correction.get("id").asString());
        assertThat(steps.movements(CostCorrectionService.SOURCE_TYPE, correctionId))
                .containsExactly("REVALUATION|0.000000|10.000000|12.0000");
        assertThat(steps.balance(warehouse, item)).isEqualTo("6.000000|72.0000|12.000000");
        assertThat(variance(correctionId)).isEqualTo("8.0000");
        assertThat(published(correctionId)).hasSize(1);
        assertThat(steps.movements(GoodsReceiptService.SOURCE_TYPE, receipt))
                .as("the receipt's own movement is a fact and stays").containsExactly(
                        "RECEIPT|10.000000|10.000000|100.0000");
    }

    @Test
    void aSecondCorrectionStartsFromTheFirstOnesCost() throws Exception {
        created(correct("12", "invoice"));
        JsonNode second = created(correct("11", "credit note"));

        assertThat(second.get("seq").asInt()).isEqualTo(2);
        assertThat(second.get("previousUnitCost").asDouble()).isEqualTo(12.0);
        assertThat(second.get("revaluationValue").asDouble()).isEqualTo(-6.0);
        assertThat(second.get("varianceValue").asDouble()).isEqualTo(-4.0);
        assertThat(steps.balance(warehouse, item)).isEqualTo("6.000000|66.0000|11.000000");
        assertThat(steps.get(store, "/receipts/" + receipt).get("lines").get(0).get("currentUnitCost").asDouble())
                .isEqualTo(11.0);
        assertThat(steps.get(store, "/receipts/" + receipt + "/cost-corrections")).hasSize(2);
    }

    @Test
    void whenEverythingWasConsumedItIsAllAVarianceAndReportingIsStillTold() throws Exception {
        steps.waste(store, warehouse, item, "6");

        JsonNode correction = created(correct("12", "invoice"));

        UUID correctionId = UUID.fromString(correction.get("id").asString());
        assertThat(correction.get("revaluationValue").asDouble()).isEqualTo(0.0);
        assertThat(correction.get("varianceValue").asDouble()).isEqualTo(20.0);
        assertThat(steps.movements(CostCorrectionService.SOURCE_TYPE, correctionId)).isEmpty();
        assertThat(variance(correctionId)).isEqualTo("20.0000");
        assertThat(published(correctionId)).singleElement()
                .satisfies(event -> assertThat(event.storeId()).isEqualTo(store));
    }

    @Test
    void goodsReturnedToTheSupplierAreNotRevaluedNorAVariance() throws Exception {
        UUID returned = id(mvc.perform(idem(post(BO + "/supplier-returns")).with(buyer(store))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"warehouseId": "%s", "goodsReceiptId": "%s",
                                 "lines": [{"receiptLineId": "%s", "stockItemId": "%s", "quantity": 2}]}"""
                                .formatted(warehouse, receipt, line, item)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        steps.command(store, "/supplier-returns/" + returned + "/confirm").andExpect(status().isOk());

        JsonNode correction = created(correct("12", "invoice"));

        assertThat(correction.get("revaluedQuantity").asDouble()).isEqualTo(4.0);
        assertThat(correction.get("revaluationValue").asDouble()).isEqualTo(8.0);
        assertThat(correction.get("varianceValue").asDouble()).isEqualTo(8.0);
    }

    @Test
    void onlyAConfirmedReceiptWithAReasonAndAPlainCostIsCorrected() throws Exception {
        UUID draft = steps.receipt(store, warehouse, supplier, PurchasingSteps.line(item, "1", "1"));
        String draftLine = steps.get(store, "/receipts/" + draft).get("lines").get(0).get("id").asString();
        correct(draft, draftLine, "{\"newUnitCost\": 2, \"reason\": \"invoice\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RECEIPT_NOT_CONFIRMED"));
        correct(receipt, line, "{\"newUnitCost\": 12}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        correct(receipt, UUID.randomUUID().toString(), "{\"newUnitCost\": 12, \"reason\": \"x\"}")
                .andExpect(status().isNotFound());
        correct(receipt, line, "{\"newUnitCost\": 1E+100000000, \"reason\": \"x\"}")
                .andExpect(status().isBadRequest());
        correct(receipt, line, "{\"newUnitCost\": 12.0000001, \"reason\": \"x\"}").andExpect(status().isBadRequest());
        correct(receipt, line, "{\"newUnitCost\": -1, \"reason\": \"x\"}").andExpect(status().isBadRequest());

        assertThat(steps.balance(warehouse, item)).isEqualTo("6.000000|60.0000|10.000000");
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.receipt_cost_correction WHERE receipt_id = ?")
                .param(receipt).query(Long.class).single()).isZero();
    }

    private ResultActions correct(String newCost, String reason) throws Exception {
        return correct(receipt, line, "{\"newUnitCost\": %s, \"reason\": \"%s\"}".formatted(newCost, reason));
    }

    private ResultActions correct(UUID onReceipt, String onLine, String body) throws Exception {
        return mvc.perform(idem(post(BO + "/receipts/" + onReceipt + "/lines/" + onLine + "/cost-corrections"))
                .with(buyer(store)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static JsonNode created(ResultActions result) throws Exception {
        return InventoryFixtures.json(result.andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
    }

    private String variance(UUID correctionId) {
        return jdbc.sql("SELECT value FROM inventory.cost_variance WHERE correction_id = ?").param(correctionId)
                .query(String.class).optional().orElse(null);
    }

    private List<StockMovementsPosted> published(UUID correctionId) {
        return events.stream(StockMovementsPosted.class)
                .filter(event -> event.sourceType().equals(CostCorrectionService.SOURCE_TYPE)
                        && event.sourceId().equals(correctionId)).toList();
    }
}
