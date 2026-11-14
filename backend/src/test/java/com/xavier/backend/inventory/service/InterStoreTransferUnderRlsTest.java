package com.xavier.backend.inventory.service;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A76: a cross-store transfer under row-level security, through the API with single-store actors (each request runs
 * in its actor's store scope). A store-A shipper creates and ships to store B's warehouse (a warehouse of another
 * store is readable, D168); a store-B receiver sees and receives it although store A's ledger is invisible to it, and
 * its TRANSFER_IN carries exactly the value the OUT took; a store-C actor gets 403 on every command (D42 through
 * {@code inventory.stock_transfer_stores}), never 404; each balance and movement is in its own store.
 */
@InventoryModuleTest
class InterStoreTransferUnderRlsTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StoreScopes scopes;

    UUID storeA;
    UUID storeB;
    UUID storeC;
    UUID warehouseA;
    UUID warehouseB;
    UUID item;

    /** 700 units worth 1.0000 in A: the average 0.001429 × 700 is 1.0003, so only the OUT's value is exact. */
    @BeforeEach
    void sevenHundredUnitsWorthOneInStoreA() throws Exception {
        storeA = fixtures.newActiveStore();
        storeB = fixtures.newActiveStore();
        storeC = fixtures.newActiveStore();
        warehouseA = fixtures.newWarehouse(storeA, "ALLOW");
        warehouseB = fixtures.newWarehouse(storeB, "ALLOW");
        item = fixtures.newStockItem("un");
        mvc.perform(idem(post(BO + "/movements/manual-adjustments")).with(account(storeA, Permission.INVENTORY_MOVE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"warehouseId": "%s", "reason": "start",
                                 "lines": [{"stockItemId": "%s", "quantity": 1, "unitCost": 1}]}"""
                                .formatted(warehouseA, item)))
                .andExpect(status().isCreated());
        mvc.perform(idem(post(BO + "/movements/manual-adjustments")).with(account(storeA, Permission.INVENTORY_MOVE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"warehouseId": "%s", "reason": "start",
                                 "lines": [{"stockItemId": "%s", "quantity": 699, "unitCost": 0}]}"""
                                .formatted(warehouseA, item)))
                .andExpect(status().isCreated());
    }

    @Test
    void aStoreAShipperAndAStoreBReceiverMoveStockEachInTheirOwnStore() throws Exception {
        UUID transfer = id(mvc.perform(idem(post(BO + "/transfers")).with(managerOf(storeA))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"fromWarehouseId": "%s", "toWarehouseId": "%s",
                                 "lines": [{"stockItemId": "%s", "quantity": 700}]}""".formatted(warehouseA,
                                warehouseB, item)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        command(transfer, "ship", managerOf(storeA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));

        mvc.perform(get(BO + "/transfers/" + transfer).with(managerOf(storeB))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));
        mvc.perform(get(BO + "/transfers").param("storeId", storeB.toString()).with(managerOf(storeB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value(transfer.toString()));
        assertThat(scopes.callForStore(storeB, "test:receiver", () -> movementsOf(transfer)))
                .as("the receiver's scope does not see the source's ledger").isEmpty();
        command(transfer, "receive", managerOf(storeB), """
                {"lines": [{"stockItemId": "%s", "quantityReceived": 700}]}""".formatted(item))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"));

        assertThat(movementsOf(transfer)).containsExactly(
                "TRANSFER_OUT|" + storeA + "|" + warehouseA + "|-700.000000|-1.0000",
                "TRANSFER_IN|" + storeB + "|" + warehouseB + "|700.000000|1.0000");
        assertThat(balance(warehouseA)).isEqualTo(storeA + "|0.000000|0.0000");
        assertThat(balance(warehouseB)).isEqualTo(storeB + "|700.000000|1.0000");
    }

    @Test
    void aThirdStoreIsForbiddenNotToldTheTransferIsAbsent() throws Exception {
        UUID transfer = id(mvc.perform(idem(post(BO + "/transfers")).with(managerOf(storeA))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"fromWarehouseId": "%s", "toWarehouseId": "%s",
                                 "lines": [{"stockItemId": "%s", "quantity": 1}]}""".formatted(warehouseA,
                                warehouseB, item)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        RequestPostProcessor storeCManager = managerOf(storeC);

        mvc.perform(get(BO + "/transfers/" + transfer).with(storeCManager)).andExpect(status().isForbidden());
        command(transfer, "ship", storeCManager).andExpect(status().isForbidden());
        command(transfer, "cancel", storeCManager).andExpect(status().isForbidden());
        command(transfer, "ship", managerOf(storeA)).andExpect(status().isOk());
        command(transfer, "receive", storeCManager, """
                {"lines": [{"stockItemId": "%s", "quantityReceived": 1}]}""".formatted(item))
                .andExpect(status().isForbidden());
        mvc.perform(get(BO + "/transfers/" + UUID.randomUUID()).with(storeCManager)).andExpect(status().isNotFound());
    }

    private ResultActions command(UUID transfer, String command, RequestPostProcessor actor) throws Exception {
        return mvc.perform(idem(post(BO + "/transfers/" + transfer + "/" + command)).with(actor));
    }

    private ResultActions command(UUID transfer, String command, RequestPostProcessor actor, String body)
            throws Exception {
        return mvc.perform(idem(post(BO + "/transfers/" + transfer + "/" + command)).with(actor)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static RequestPostProcessor managerOf(UUID store) {
        return account(store, Permission.INVENTORY_TRANSFER, Permission.INVENTORY_VIEW);
    }

    private String balance(UUID warehouse) {
        return jdbc.sql("""
                        SELECT store_id || '|' || quantity || '|' || value FROM inventory.stock_balance
                        WHERE warehouse_id = ? AND stock_item_id = ?""")
                .params(warehouse, item).query(String.class).single();
    }

    private List<String> movementsOf(UUID transfer) {
        return jdbc.sql("""
                        SELECT type || '|' || store_id || '|' || warehouse_id || '|' || quantity || '|' || value
                        FROM inventory.stock_movement WHERE source_type = 'TRANSFER' AND source_id = ?
                        ORDER BY posting_seq""")
                .param(transfer).query(String.class).list();
    }
}
