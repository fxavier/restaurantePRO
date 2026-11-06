package com.xavier.backend.inventory.controller;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.PostMovement;
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

/**
 * The stock reports (D131, valuation half of A52): valuation = Σ balances, below minimum, waste and rotation over
 * business days, {@code INVENTORY_VIEW} in the store; as CSV with {@code REPORT_EXPORT} too, audited.
 */
@InventoryModuleTest
class InventoryReportHttpTest {

    static final LocalDate DAY_1 = LocalDate.parse("2026-11-02");
    static final LocalDate DAY_2 = DAY_1.plusDays(1);
    static final Instant AT = Instant.parse("2026-11-03T10:00:00Z");

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    StockLedger ledger;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    UUID store;
    UUID kitchen;
    UUID bar;
    UUID flour;
    UUID lemons;

    /** Day 1: flour 10 @ 2 in the kitchen, lemons 20 @ 0.5 in the bar. Day 2: 4 flour sold, 1 wasted, 2 lemons wasted. */
    @BeforeEach
    void twoDays() throws Exception {
        store = fixtures.newStore();
        kitchen = fixtures.newWarehouse(store, "ALLOW");
        bar = fixtures.newWarehouse(store, "ALLOW");
        flour = fixtures.newStockItem("kg");
        lemons = fixtures.newStockItem("un");
        postMovements(in(kitchen, flour, "10", "2"), in(bar, lemons, "20", "0.5"));
        postMovements(PostMovement.of(kitchen, flour, MovementType.SALE_CONSUMPTION, new BigDecimal("-4"), "ORDER_LINE",
                UUID.randomUUID(), DAY_2, AT));
        postMovements(out(kitchen, flour, "-1"), out(bar, lemons, "-2"));
        mvc.perform(idem(post(BO + "/stock-levels")).with(account(store, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"warehouseId": "%s", "stockItemId": "%s", "minQuantity": 8}""".formatted(kitchen, flour)))
                .andExpect(status().isCreated());
    }

    @Test
    void theValuationIsTheSumOfTheBalances() throws Exception {
        BigDecimal balances = jdbc.sql("""
                        SELECT sum(b.value) FROM inventory.stock_balance b JOIN inventory.warehouse w ON w.id = b.warehouse_id
                        WHERE w.store_id = ?""").param(store).query(BigDecimal.class).single();

        mvc.perform(get(BO + "/reports/valuation").param("storeId", store.toString()).with(viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.totalValue").value(19.0));
        assertThat(balances).isEqualByComparingTo("19");
    }

    @Test
    void belowMinimumWasteAndRotation() throws Exception {
        mvc.perform(get(BO + "/reports/below-minimum").param("storeId", store.toString()).with(viewer()))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].stockItemId").value(flour.toString()))
                .andExpect(jsonPath("$.rows[0].missing").value(3.0));
        mvc.perform(get(BO + "/reports/waste").param("storeId", store.toString()).param("from", DAY_2.toString())
                        .param("to", DAY_2.toString()).with(viewer()))
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.totalValue").value(3.0));
        // flour: consumed 8.00 on day 2; opening 20.00, closing 10.00 → average 15.00 → rotation 0.5333
        mvc.perform(get(BO + "/reports/rotation").param("storeId", store.toString()).param("from", DAY_2.toString())
                        .param("to", DAY_2.toString()).with(viewer()))
                .andExpect(jsonPath("$.rows[?(@.stockItemId == '%s')].consumptionCost".formatted(flour)).value(8.0))
                .andExpect(jsonPath("$.rows[?(@.stockItemId == '%s')].averageValue".formatted(flour)).value(15.0))
                .andExpect(jsonPath("$.rows[?(@.stockItemId == '%s')].rotation".formatted(flour)).value(0.5333));
        mvc.perform(get(BO + "/reports/waste").param("storeId", store.toString()).param("from", DAY_2.toString())
                        .param("to", DAY_1.toString()).with(viewer()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
    }

    @Test
    void aCsvExportNeedsReportExportAndIsAudited() throws Exception {
        mvc.perform(get(BO + "/reports/valuation").param("storeId", store.toString()).param("format", "csv")
                        .with(viewer()))
                .andExpect(status().isForbidden());

        String csv = mvc.perform(get(BO + "/reports/valuation").param("storeId", store.toString())
                        .param("format", "csv").with(account(store, Permission.INVENTORY_VIEW, Permission.REPORT_EXPORT)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();

        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("warehouseCode,code,name,baseUnit,quantity,averageCost,value");
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'INVENTORY_REPORT_EXPORTED' "
                + "AND store_id = ?").param(store).query(Long.class).single()).isEqualTo(1);
        mvc.perform(get(BO + "/reports/waste").param("storeId", store.toString()).param("format", "csv")
                        .with(account(store, Permission.INVENTORY_VIEW, Permission.REPORT_EXPORT)))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
    }

    @Test
    void reportsNeedInventoryViewInTheStore() throws Exception {
        mvc.perform(get(BO + "/reports/valuation").param("storeId", store.toString())
                        .with(account(UUID.randomUUID(), Permission.INVENTORY_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BO + "/reports/valuation").param("storeId", store.toString())
                        .with(account(store, Permission.REPORT_EXPORT)))
                .andExpect(status().isForbidden());
    }

    private PostMovement in(UUID warehouse, UUID item, String quantity, String cost) {
        return PostMovement.of(warehouse, item, MovementType.RECEIPT, new BigDecimal(quantity), "GOODS_RECEIPT",
                UUID.randomUUID(), DAY_1, AT).atCost(new BigDecimal(cost));
    }

    private PostMovement out(UUID warehouse, UUID item, String quantity) {
        return PostMovement.of(warehouse, item, MovementType.WASTE, new BigDecimal(quantity), "MANUAL_MOVEMENT",
                UUID.randomUUID(), DAY_2, AT);
    }

    private void postMovements(PostMovement... movements) {
        tx.executeWithoutResult(status -> ledger.post(List.of(movements)));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor viewer() {
        return account(store, Permission.INVENTORY_VIEW);
    }
}
