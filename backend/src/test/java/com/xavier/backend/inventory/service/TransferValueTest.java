package com.xavier.backend.inventory.service;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S47 review round 4 (D113 as amended): a TRANSFER_IN carries exactly the value its TRANSFER_OUT took, so a transfer
 * never creates or destroys value, even from a balance whose value has drifted from quantity × average (here 1000 g
 * at 0.00015, 750 of them sold gram by gram). Within one store (ship receives) and between stores (receive later).
 */
@InventoryModuleTest
class TransferValueTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-06");
    static final Instant AT = Instant.parse("2026-11-06T10:00:00Z");

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
    UUID source;
    UUID item;

    @BeforeEach
    void aDriftedBalance() throws Exception {
        store = fixtures.newActiveStore();
        source = fixtures.newWarehouse(store, "ALLOW");
        item = fixtures.newStockItem("g");
        tx.executeWithoutResult(status -> {
            ledger.post(List.of(PostMovement.of(source, item, MovementType.RECEIPT, new BigDecimal("1000"),
                    "GOODS_RECEIPT", UUID.randomUUID(), DAY, AT).atCost(new BigDecimal("0.000150"))));
            for (int i = 0; i < 750; i++) {
                ledger.post(List.of(PostMovement.of(source, item, MovementType.SALE_CONSUMPTION, BigDecimal.ONE.negate(),
                        "ORDER_LINE", UUID.randomUUID(), DAY, AT)));
            }
        });
    }

    @Test
    void aTransferWithinOneStoreKeepsTheValue() throws Exception {
        UUID target = fixtures.newWarehouse(store, "ALLOW");
        BigDecimal before = value(source).add(value(target));
        UUID transfer = draft(target, managerOf(store));

        command(transfer, "ship", managerOf(store));

        assertThat(quantity(target)).isEqualByComparingTo("100");
        assertThat(value(source).add(value(target))).isEqualByComparingTo(before);
        assertThat(value(source)).isNotNegative();
    }

    @Test
    void aTransferBetweenStoresKeepsTheValue() throws Exception {
        UUID other = fixtures.newStore();
        UUID target = fixtures.newWarehouse(other, "ALLOW");
        BigDecimal before = value(source).add(value(target));
        UUID transfer = draft(target, managerOf(store));
        command(transfer, "ship", managerOf(store));

        mvc.perform(idem(post(BO + "/transfers/" + transfer + "/receive")).with(managerOf(other))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"lines": [{"stockItemId": "%s", "quantityReceived": 100}]}""".formatted(item)))
                .andExpect(status().isOk());

        assertThat(quantity(target)).isEqualByComparingTo("100");
        assertThat(value(source).add(value(target))).isEqualByComparingTo(before);
    }

    private UUID draft(UUID target, org.springframework.test.web.servlet.request.RequestPostProcessor actor)
            throws Exception {
        return id(mvc.perform(idem(post(BO + "/transfers")).with(actor).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromWarehouseId": "%s", "toWarehouseId": "%s",
                                 "lines": [{"stockItemId": "%s", "quantity": 100}]}""".formatted(source, target, item)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void command(UUID transfer, String command,
                         org.springframework.test.web.servlet.request.RequestPostProcessor actor) throws Exception {
        mvc.perform(idem(post(BO + "/transfers/" + transfer + "/" + command)).with(actor)).andExpect(status().isOk());
    }

    private BigDecimal value(UUID warehouse) {
        return jdbc.sql("SELECT value FROM inventory.stock_balance WHERE warehouse_id = ? AND stock_item_id = ?")
                .params(warehouse, item).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    private BigDecimal quantity(UUID warehouse) {
        return jdbc.sql("SELECT quantity FROM inventory.stock_balance WHERE warehouse_id = ? AND stock_item_id = ?")
                .params(warehouse, item).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor managerOf(UUID store) {
        return account(store, Permission.INVENTORY_TRANSFER, Permission.INVENTORY_VIEW);
    }
}
