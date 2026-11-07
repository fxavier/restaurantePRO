package com.xavier.backend.inventory.purchasing.service;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static com.xavier.backend.inventory.purchasing.PurchasingSteps.buyer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.purchasing.PurchasingSteps;
import com.xavier.backend.inventory.service.StockLedger;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * D125 amended: a correction revalues only the corrected line's own share still in stock under the moving average,
 * never another receipt's goods, and never takes the balance value below zero; the rest is a cost variance. Its new
 * cost is per the line's purchase unit, like every purchasing request.
 */
@InventoryModuleTest
class CostCorrectionShareTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StockLedger ledger;

    @Autowired
    TransactionTemplate tx;

    PurchasingSteps steps;
    UUID store;
    UUID warehouse;
    UUID item;
    UUID supplier;

    @BeforeEach
    void aLiveStore() throws Exception {
        steps = new PurchasingSteps(mvc, jdbc);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        item = fixtures.newStockItem("un");
        supplier = steps.newSupplier();
    }

    /** Scenario A: A consumed, B in stock: correcting A never revalues B's goods. */
    @Test
    void aConsumedReceiptIsAllVarianceEvenWhenAnotherReceiptRefilledTheStock() throws Exception {
        UUID a = steps.confirmedReceipt(store, warehouse, supplier, item, "10", "10");
        steps.waste(store, warehouse, item, "10");
        steps.confirmedReceipt(store, warehouse, supplier, item, "10", "1");

        JsonNode correction = correct(a, "0");

        assertThat(correction.get("revaluationValue").asDouble()).isEqualTo(0.0);
        assertThat(correction.get("varianceValue").asDouble()).isEqualTo(-100.0);
        assertThat(steps.balance(warehouse, item)).isEqualTo("10.000000|10.0000|1.000000");
    }

    /** Scenario B: A 10@10 and B 10@1, 10 consumed at 5.5: half of A is left, so A to 0 revalues −50. */
    @Test
    void theLinesProportionalShareIsRevalued() throws Exception {
        UUID a = steps.confirmedReceipt(store, warehouse, supplier, item, "10", "10");
        steps.confirmedReceipt(store, warehouse, supplier, item, "10", "1");
        steps.waste(store, warehouse, item, "10");

        JsonNode correction = correct(a, "0");

        assertThat(correction.get("revaluedQuantity").asDouble()).isEqualTo(5.0);
        assertThat(correction.get("revaluationValue").asDouble()).isEqualTo(-50.0);
        assertThat(correction.get("varianceValue").asDouble()).isEqualTo(-50.0);
        assertThat(steps.balance(warehouse, item)).isEqualTo("10.000000|5.0000|0.500000");
    }

    @Test
    void chainedCorrectionsEachRevalueTheShareLeftAtTheirTime() throws Exception {
        UUID a = steps.confirmedReceipt(store, warehouse, supplier, item, "10", "10");
        correct(a, "12");
        steps.waste(store, warehouse, item, "5");

        JsonNode second = correct(a, "11");

        assertThat(second.get("previousUnitCost").asDouble()).isEqualTo(12.0);
        assertThat(second.get("revaluationValue").asDouble()).isEqualTo(-5.0);
        assertThat(second.get("varianceValue").asDouble()).isEqualTo(-5.0);
        assertThat(steps.balance(warehouse, item)).isEqualTo("5.000000|55.0000|11.000000");
    }

    /**
     * B's own return takes its own quantity off B's share; the later waste takes its proportion: 10@1, B 10@2, 5 of B
     * returned (B keeps 5 of the 15 on hand), 5 wasted (B keeps 5 × 10/15). Correcting B to 3 revalues 3.333333.
     */
    @Test
    void aReturnOfTheLineTakesItsOwnQuantityAndLaterOutsTheirProportion() throws Exception {
        steps.confirmedReceipt(store, warehouse, supplier, item, "10", "1");
        UUID b = steps.confirmedReceipt(store, warehouse, supplier, item, "10", "2");
        String line = steps.get(store, "/receipts/" + b).get("lines").get(0).get("id").asString();
        UUID returned = InventoryFixtures.id(mvc.perform(idem(post(BO + "/supplier-returns")).with(buyer(store))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\": \"%s\", \"goodsReceiptId\": \"%s\", \"lines\": [{\"receiptLineId\": \"%s\", \"stockItemId\": \"%s\", \"quantity\": 5}]}"
                                .formatted(warehouse, b, line, item)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        steps.command(store, "/supplier-returns/" + returned + "/confirm").andExpect(status().isOk());
        steps.waste(store, warehouse, item, "5");

        JsonNode correction = correct(b, "3");

        assertThat(correction.get("revaluedQuantity").asString()).isEqualTo("3.333333");
        assertThat(correction.get("revaluationValue").asString()).isEqualTo("3.3333");
        assertThat(correction.get("varianceValue").asString()).isEqualTo("1.6667");
    }

    @Test
    void aDownwardCorrectionNeverTakesTheValueBelowZero() throws Exception {
        UUID a = steps.confirmedReceipt(store, warehouse, supplier, item, "10", "10");
        tx.executeWithoutResult(status -> ledger.post(List.of(PostMovement.revaluation(warehouse, item,
                new BigDecimal("-80.0000"), "TEST_REVALUATION", UUID.randomUUID(), LocalDate.parse("2026-11-07"),
                Instant.parse("2026-11-07T10:00:00Z")))));

        JsonNode correction = correct(a, "5");

        assertThat(correction.get("revaluationValue").asDouble()).isEqualTo(-20.0);
        assertThat(correction.get("revaluedQuantity").asDouble()).as("the quantity the capped value stands for: 20 ÷ 5")
                .isEqualTo(4.0);
        assertThat(correction.get("varianceValue").asDouble()).isEqualTo(-30.0);
        assertThat(steps.balance(warehouse, item)).startsWith("10.000000|0.0000|");
    }

    @Test
    void theNewCostIsPerTheLinesPurchaseUnit() throws Exception {
        UUID box = steps.newPack(item, "12");
        UUID a = steps.receipt(store, warehouse, supplier, """
                {"stockItemId": "%s", "packId": "%s", "packQuantity": 2, "unitCost": 120}""".formatted(item, box));
        steps.command(store, "/receipts/" + a + "/confirm").andExpect(status().isOk());

        JsonNode correction = correct(a, "144");

        assertThat(correction.get("newUnitCost").asDouble()).isEqualTo(12.0);
        assertThat(correction.get("revaluationValue").asDouble()).isEqualTo(48.0);
        assertThat(steps.balance(warehouse, item)).isEqualTo("24.000000|288.0000|12.000000");
    }

    private JsonNode correct(UUID receipt, String newCost) throws Exception {
        String line = steps.get(store, "/receipts/" + receipt).get("lines").get(0).get("id").asString();
        return InventoryFixtures.json(mvc.perform(idem(post(BO + "/receipts/" + receipt + "/lines/" + line
                        + "/cost-corrections")).with(buyer(store)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newUnitCost\": %s, \"reason\": \"invoice\"}".formatted(newCost)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
}
