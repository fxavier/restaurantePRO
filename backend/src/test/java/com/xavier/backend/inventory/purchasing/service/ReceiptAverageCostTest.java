package com.xavier.backend.inventory.purchasing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.purchasing.PurchasingSteps;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/** A46: receipts at 10.00 then 12.00 for 10 units each → average 11.00; consuming 5 → value −55.00. */
@InventoryModuleTest
class ReceiptAverageCostTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Test
    void receiptsAtDifferentCostsAverageAndAnOutIsValuedAtTheAverage() throws Exception {
        PurchasingSteps steps = new PurchasingSteps(mvc, jdbc);
        UUID store = fixtures.newActiveStore();
        UUID warehouse = fixtures.newWarehouse(store, "ALLOW");
        UUID item = fixtures.newStockItem("un");
        UUID supplier = steps.newSupplier();

        steps.confirmedReceipt(store, warehouse, supplier, item, "10", "10.00");
        steps.confirmedReceipt(store, warehouse, supplier, item, "10", "12.00");
        assertThat(steps.balance(warehouse, item)).isEqualTo("20.000000|220.0000|11.000000");

        steps.waste(store, warehouse, item, "5");

        assertThat(jdbc.sql("""
                        SELECT value FROM inventory.stock_movement
                        WHERE warehouse_id = ? AND stock_item_id = ? AND type = 'WASTE'""")
                .params(warehouse, item).query(String.class).single()).isEqualTo("-55.0000");
        assertThat(steps.balance(warehouse, item)).isEqualTo("15.000000|165.0000|11.000000");
    }
}
