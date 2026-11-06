package com.xavier.backend.inventory.service;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.repository.BalanceDrift;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The balance is a reconcilable projection (D113, A49, D133 {@code StockBalanceReconcileTest}): after a seeded day
 * Σ movements = balance; the nightly check detects a tampered balance (ERROR log with its ids, gauge
 * {@code inventory_balance_drift}), and the rebuild replays the ledger in posting order back to the same balances and
 * averages.
 */
@InventoryModuleTest
@ExtendWith(OutputCaptureExtension.class)
class StockBalanceReconcileTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-06");
    static final Instant AT = Instant.parse("2026-11-06T10:00:00Z");

    @Autowired
    StockLedger ledger;

    @Autowired
    StockBalanceReconciliationJob job;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    MockMvc mvc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    UUID store;
    UUID warehouse;
    UUID flour;
    UUID oil;

    /** A day: receipts at two costs, sales (one below zero and back), waste, a return. */
    @BeforeEach
    void aSeededDay() throws Exception {
        store = fixtures.newStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        flour = fixtures.newStockItem("kg");
        oil = fixtures.newStockItem("l");
        postMovements(receipt(flour, "10", "2.00"), receipt(oil, "3", "7.333333"));
        postMovements(sale(flour, "-4"), sale(oil, "-1.25"));
        postMovements(receipt(flour, "5", "2.60"));
        postMovements(sale(oil, "-3"));
        postMovements(receipt(oil, "6", "8.10"));
        postMovements(PostMovement.of(warehouse, flour, MovementType.WASTE, new BigDecimal("-0.333"), "MANUAL_MOVEMENT",
                UUID.randomUUID(), DAY, AT));
        postMovements(PostMovement.of(warehouse, oil, MovementType.RETURN_TO_STOCK, new BigDecimal("0.5"), "MANUAL_MOVEMENT",
                UUID.randomUUID(), DAY, AT));
    }

    @Test
    void afterASeededDayTheMovementsAddUpToTheBalance() {
        assertThat(driftsHere()).isEmpty();
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM inventory.stock_movement
                        WHERE warehouse_id = ? AND type = 'REVALUATION'""")
                .param(warehouse).query(Long.class).single()).as("oil went below zero and back").isEqualTo(1);
    }

    @Test
    void aTamperedBalanceIsDetectedAndTheRebuildRestoresItsQuantityValueAndAverage(CapturedOutput output)
            throws Exception {
        Map<UUID, String> before = balances();
        jdbc.sql("""
                        UPDATE inventory.stock_balance SET quantity = quantity + 1, value = value + 5, average_cost = 99
                        WHERE warehouse_id = ? AND stock_item_id = ?""")
                .params(warehouse, flour).update();

        List<BalanceDrift> drifts = driftsHere();

        assertThat(drifts).singleElement().satisfies(drift -> {
            assertThat(drift.stockItemId()).isEqualTo(flour);
            assertThat(drift.storeId()).isEqualTo(store);
            assertThat(drift.balanceQuantity().subtract(drift.ledgerQuantity())).isEqualByComparingTo("1");
            assertThat(drift.balanceValue().subtract(drift.ledgerValue())).isEqualByComparingTo("5");
        });
        assertThat(meters.get("inventory_balance_drift").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(output.getOut()).contains("Stock balance drift").contains(warehouse.toString())
                .contains(flour.toString());

        mvc.perform(idem(post(BO + "/balances/rebuild")).with(account(store, Permission.INVENTORY_REBUILD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"warehouseId\": \"%s\"}".formatted(warehouse)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balances").value(2))
                .andExpect(jsonPath("$.corrected").value(1));

        assertThat(driftsHere()).isEmpty();
        assertThat(balances()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'STOCK_BALANCES_REBUILT' "
                + "AND entity_id = ?").param(warehouse.toString()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aDeletedBalanceRowIsDetectedAndRebuilt() throws Exception {
        Map<UUID, String> before = balances();
        jdbc.sql("DELETE FROM inventory.stock_balance WHERE warehouse_id = ? AND stock_item_id = ?")
                .params(warehouse, oil).update();

        assertThat(driftsHere()).extracting(BalanceDrift::stockItemId).containsExactly(oil);

        mvc.perform(idem(post(BO + "/balances/rebuild")).with(account(null, Permission.INVENTORY_REBUILD))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(balances()).isEqualTo(before);
    }

    @Test
    void rebuildingNeedsInventoryRebuildInTheStoreOrInstallationWideForEverything() throws Exception {
        mvc.perform(idem(post(BO + "/balances/rebuild")).with(account(UUID.randomUUID(), Permission.INVENTORY_REBUILD))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"warehouseId\": \"%s\"}".formatted(warehouse)))
                .andExpect(status().isForbidden());
        mvc.perform(idem(post(BO + "/balances/rebuild")).with(account(store, Permission.INVENTORY_REBUILD))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(idem(post(BO + "/balances/rebuild")).with(account(store, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"warehouseId\": \"%s\"}".formatted(warehouse)))
                .andExpect(status().isForbidden());
    }

    private List<BalanceDrift> driftsHere() {
        return job.reconcile().stream().filter(drift -> drift.warehouseId().equals(warehouse)).toList();
    }

    private Map<UUID, String> balances() {
        return jdbc.sql("""
                        SELECT stock_item_id, quantity || '|' || value || '|' || average_cost AS position
                        FROM inventory.stock_balance WHERE warehouse_id = ?""")
                .param(warehouse)
                .query((rs, n) -> Map.entry(rs.getObject("stock_item_id", UUID.class), rs.getString("position")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void postMovements(PostMovement... movements) {
        tx.executeWithoutResult(status -> ledger.post(List.of(movements)));
    }

    private PostMovement receipt(UUID item, String quantity, String cost) {
        return PostMovement.of(warehouse, item, MovementType.RECEIPT, new BigDecimal(quantity), "GOODS_RECEIPT",
                UUID.randomUUID(), DAY, AT).atCost(new BigDecimal(cost));
    }

    private PostMovement sale(UUID item, String quantity) {
        return PostMovement.of(warehouse, item, MovementType.SALE_CONSUMPTION, new BigDecimal(quantity), "ORDER_LINE",
                UUID.randomUUID(), DAY, AT);
    }
}
