package com.xavier.backend.inventory.purchasing.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.purchasing.PurchasingSteps;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The database guards the purchasing facts too (§8): lines of a submitted order, a confirmed receipt or return are
 * immutable, cost corrections and variances are append-only, and receipts are one line per stock item.
 */
@InventoryModuleTest
class PurchasingConstraintsTest {

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
    void aLiveStore() throws Exception {
        steps = new PurchasingSteps(mvc, jdbc);
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        item = fixtures.newStockItem("un");
        supplier = steps.newSupplier();
    }

    @Test
    void aDraftReceiptsLinesChangeButAConfirmedReceiptsNever() throws Exception {
        UUID receipt = steps.receipt(store, warehouse, supplier, PurchasingSteps.line(item, "10", "10"));
        assertThat(jdbc.sql("UPDATE inventory.goods_receipt_line SET unit_cost_base = 9 WHERE receipt_id = ?")
                .param(receipt).update()).isEqualTo(1);
        steps.command(store, "/receipts/" + receipt + "/confirm");

        assertRefused("UPDATE inventory.goods_receipt_line SET unit_cost_base = 11 WHERE receipt_id = ?", receipt);
        assertRefused("DELETE FROM inventory.goods_receipt_line WHERE receipt_id = ?", receipt);
        assertRefused("""
                INSERT INTO inventory.goods_receipt_line (id, receipt_id, stock_item_id, pack_quantity, quantity_base,
                    unit_cost_base) VALUES (gen_random_uuid(), ?, ?, 1, 1, 1)""", receipt, fixtures.newStockItem("un"));
    }

    @Test
    void aSubmittedOrdersLinesAreImmutable() throws Exception {
        UUID order = InventoryFixtures.id(mvc.perform(InventoryFixtures.idem(org.springframework.test.web.servlet
                        .request.MockMvcRequestBuilders.post(InventoryFixtures.BO + "/purchase-orders"))
                        .with(PurchasingSteps.buyer(store)).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId": "%s", "supplierId": "%s", "number": "%s",
                                 "lines": [{"stockItemId": "%s", "quantity": 1, "unitCost": 1}]}"""
                                .formatted(warehouse, supplier, InventoryFixtures.code("PO"), item)))
                .andReturn().getResponse().getContentAsString());
        steps.command(store, "/purchase-orders/" + order + "/submit");

        assertRefused("UPDATE inventory.purchase_order_line SET quantity = 2 WHERE purchase_order_id = ?", order);
        assertRefused("DELETE FROM inventory.purchase_order_line WHERE purchase_order_id = ?", order);
    }

    @Test
    void costCorrectionsAndVariancesAreAppendOnly() throws Exception {
        UUID receipt = steps.confirmedReceipt(store, warehouse, supplier, item, "10", "10");
        steps.waste(store, warehouse, item, "4");
        String line = steps.get(store, "/receipts/" + receipt).get("lines").get(0).get("id").asString();
        mvc.perform(InventoryFixtures.idem(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post(InventoryFixtures.BO + "/receipts/" + receipt + "/lines/" + line + "/cost-corrections"))
                .with(PurchasingSteps.buyer(store)).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"newUnitCost\": 12, \"reason\": \"invoice\"}"));

        assertRefused("UPDATE inventory.receipt_cost_correction SET new_unit_cost = 13 WHERE receipt_id = ?", receipt);
        assertRefused("DELETE FROM inventory.receipt_cost_correction WHERE receipt_id = ?", receipt);
        assertRefused("""
                UPDATE inventory.cost_variance SET value = 1 WHERE correction_id IN
                    (SELECT id FROM inventory.receipt_cost_correction WHERE receipt_id = ?)""", receipt);
    }

    @Test
    void aReceiptHasOneLinePerStockItem() throws Exception {
        UUID receipt = steps.receipt(store, warehouse, supplier, PurchasingSteps.line(item, "1", "1"));

        assertRefused("""
                INSERT INTO inventory.goods_receipt_line (id, receipt_id, stock_item_id, pack_quantity, quantity_base,
                    unit_cost_base) VALUES (gen_random_uuid(), ?, ?, 1, 1, 1)""", receipt, item);
    }

    private void assertRefused(String sql, Object... params) {
        assertThatThrownBy(() -> jdbc.sql(sql).params(params).update()).isInstanceOf(DataAccessException.class);
    }
}
