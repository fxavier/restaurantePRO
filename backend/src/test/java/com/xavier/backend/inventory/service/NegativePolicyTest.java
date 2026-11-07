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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Negative stock (D118, P41): sales always post, even below zero. A warehouse set to {@code REFUSE_MANUAL} refuses
 * a manual outgoing movement that would leave it below zero (422 {@code NEGATIVE_STOCK_REFUSED}, nothing posted);
 * one set to {@code ALLOW} (the default) lets it through.
 */
@InventoryModuleTest
class NegativePolicyTest {

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

    @Test
    void aRefusingWarehouseRefusesWasteBelowZeroButAllowsDownToZero() throws Exception {
        Stock stock = stockOf2("REFUSE_MANUAL");

        manual(stock, "waste", "3").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NEGATIVE_STOCK_REFUSED"))
                .andExpect(jsonPath("$.available").value(2.0))
                .andExpect(jsonPath("$.stockItemId").value(stock.item().toString()));
        manual(stock, "manual-adjustments", "-2.000001").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NEGATIVE_STOCK_REFUSED"));
        assertThat(quantity(stock)).isEqualByComparingTo("2");

        manual(stock, "waste", "2").andExpect(status().isCreated());
        assertThat(quantity(stock)).isEqualByComparingTo("0");
    }

    @Test
    void aSaleAlwaysPostsEvenInARefusingWarehouse() throws Exception {
        Stock stock = stockOf2("REFUSE_MANUAL");

        tx.executeWithoutResult(status -> ledger.post(List.of(PostMovement.of(stock.warehouse(), stock.item(),
                MovementType.SALE_CONSUMPTION, new BigDecimal("-5"), "ORDER_LINE", UUID.randomUUID(),
                LocalDate.parse("2026-11-06"), Instant.parse("2026-11-06T12:00:00Z")))));

        assertThat(quantity(stock)).isEqualByComparingTo("-3");
        manual(stock, "returns-to-stock", "1").andExpect(status().isCreated());
        assertThat(quantity(stock)).isEqualByComparingTo("-2");
    }

    @Test
    void anAllowingWarehouseLetsManualMovementsGoBelowZero() throws Exception {
        Stock stock = stockOf2("ALLOW");

        manual(stock, "waste", "3").andExpect(status().isCreated());

        assertThat(quantity(stock)).isEqualByComparingTo("-1");
    }

    /** A redelivered manual OUT is answered as recorded, even though the stock it left now refuses it. */
    @Test
    void aReplayedManualOutIsAnsweredAsRecordedEvenWhenItWouldNowBeRefused() throws Exception {
        Stock stock = stockOf2("REFUSE_MANUAL");
        PostMovement waste = PostMovement.of(stock.warehouse(), stock.item(), MovementType.WASTE,
                new BigDecimal("-2"), "MANUAL_MOVEMENT", UUID.randomUUID(), LocalDate.parse("2026-11-06"),
                Instant.parse("2026-11-06T12:00:00Z")).manual("broken");
        var first = tx.execute(status -> ledger.post(List.of(waste)));

        var again = tx.execute(status -> ledger.post(List.of(waste)));

        assertThat(again).isEqualTo(first);
        assertThat(quantity(stock)).isEqualByComparingTo("0");
    }

    private Stock stockOf2(String policy) throws Exception {
        UUID store = fixtures.newActiveStore();
        Stock stock = new Stock(store, fixtures.newWarehouse(store, policy), fixtures.newStockItem("un"));
        mvc.perform(idem(post(BO + "/movements/manual-adjustments")).with(account(store, Permission.INVENTORY_MOVE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"warehouseId": "%s", "reason": "start",
                                 "lines": [{"stockItemId": "%s", "quantity": 2, "unitCost": 1}]}"""
                                .formatted(stock.warehouse(), stock.item())))
                .andExpect(status().isCreated());
        return stock;
    }

    private ResultActions manual(Stock stock, String path, String quantity) throws Exception {
        return mvc.perform(idem(post(BO + "/movements/" + path)).with(account(stock.store(), Permission.INVENTORY_MOVE))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"warehouseId": "%s", "reason": "test",
                         "lines": [{"stockItemId": "%s", "quantity": %s, "unitCost": 1}]}"""
                        .formatted(stock.warehouse(), stock.item(), quantity)));
    }

    private BigDecimal quantity(Stock stock) {
        return jdbc.sql("SELECT quantity FROM inventory.stock_balance WHERE warehouse_id = ? AND stock_item_id = ?")
                .params(stock.warehouse(), stock.item()).query(BigDecimal.class).single();
    }

    private record Stock(UUID store, UUID warehouse, UUID item) {
    }
}
