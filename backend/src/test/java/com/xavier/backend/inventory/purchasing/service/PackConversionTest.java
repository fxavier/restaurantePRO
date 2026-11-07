package com.xavier.backend.inventory.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.purchasing.PurchasingSteps;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D125: receipt lines are entered in packs and their invoiced price per pack; they are received in base units at a
 * cost per base unit (scale 6), and that is what the ledger and the last cost record.
 */
@InventoryModuleTest
class PackConversionTest {

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
    void aLiveStoreAndASupplier() throws Exception {
        steps = new PurchasingSteps(mvc, jdbc);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        supplier = steps.newSupplier();
    }

    @Test
    void twoBoxesOf12At120EachAre24UnitsAt10() throws Exception {
        UUID item = fixtures.newStockItem("un");
        UUID box = steps.newPack(item, "12");
        UUID receipt = receiptInPacks(item, box, "2", "120");

        steps.command(store, "/receipts/" + receipt + "/confirm").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].packQuantity").value(2.0))
                .andExpect(jsonPath("$.lines[0].quantityBase").value(24.0))
                .andExpect(jsonPath("$.lines[0].unitCostBase").value(10.0));

        assertThat(steps.movements(GoodsReceiptService.SOURCE_TYPE, receipt))
                .containsExactly("RECEIPT|24.000000|10.000000|240.0000");
        assertThat(steps.get(store, "/suppliers/" + supplier + "/items").get(0).get("packId").asString())
                .isEqualTo(box.toString());
    }

    @Test
    void twoSacksOf25KgAt50EachAre50000GramsAtTwoThousandths() throws Exception {
        UUID flour = fixtures.newStockItem("g");
        UUID sack = steps.newPack(flour, "25000");
        UUID receipt = receiptInPacks(flour, sack, "2", "50");

        steps.command(store, "/receipts/" + receipt + "/confirm").andExpect(status().isOk());

        assertThat(steps.movements(GoodsReceiptService.SOURCE_TYPE, receipt))
                .containsExactly("RECEIPT|50000.000000|0.002000|100.0000");
    }

    @Test
    void aPackOfAnotherItemIsRefused() throws Exception {
        UUID item = fixtures.newStockItem("un");
        UUID otherBox = steps.newPack(fixtures.newStockItem("un"), "6");

        steps.newReceipt(store, warehouse, supplier, packLine(item, otherBox, "1", "6"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PURCHASE_REFERENCE_MISMATCH"));
    }

    /** Quantities and values are bounded before anything is locked: a coded 422, never a numeric overflow. */
    @Test
    void aQuantityThatConvertsToNothingOrTooMuchAndAnOversizedLineValueAreRefused() throws Exception {
        UUID item = fixtures.newStockItem("un");
        UUID tenth = steps.newPack(item, "0.1");
        UUID box = steps.newPack(item, "12");

        steps.newReceipt(store, warehouse, supplier, packLine(item, tenth, "0.000001", "1"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
        steps.newReceipt(store, warehouse, supplier, packLine(item, box, "9999999999999", "1"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
        steps.newReceipt(store, warehouse, supplier, PurchasingSteps.line(item, "9999999999999", "9999999999999"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LINE_VALUE_TOO_LARGE"));
    }

    private UUID receiptInPacks(UUID item, UUID pack, String packs, String pricePerPack) throws Exception {
        return steps.receipt(store, warehouse, supplier, packLine(item, pack, packs, pricePerPack));
    }

    private static String packLine(UUID item, UUID pack, String packs, String pricePerPack) {
        return "{\"stockItemId\": \"%s\", \"packId\": \"%s\", \"packQuantity\": %s, \"unitCost\": %s}"
                .formatted(item, pack, packs, pricePerPack);
    }
}
