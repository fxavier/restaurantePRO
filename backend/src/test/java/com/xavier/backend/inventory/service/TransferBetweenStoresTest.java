package com.xavier.backend.inventory.service;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
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
 * Transfers (D120, A48). Between stores: SHIPPED lowers the source at its average; RECEIVED raises the destination
 * by what arrived and records a TRANSFER_SHORTAGE for the rest at the same cost. {@code INVENTORY_TRANSFER} is
 * checked at the source store to ship and at the destination store to receive. Within one store, shipping receives.
 */
@InventoryModuleTest
class TransferBetweenStoresTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    UUID storeA;
    UUID storeB;
    UUID warehouseA;
    UUID warehouseB;
    UUID item;

    @BeforeEach
    void tenUnitsAt3InStoreA() throws Exception {
        storeA = fixtures.newStore();
        storeB = fixtures.newStore();
        warehouseA = fixtures.newWarehouse(storeA, "ALLOW");
        warehouseB = fixtures.newWarehouse(storeB, "ALLOW");
        item = fixtures.newStockItem("un");
        mvc.perform(idem(post(BO + "/movements/manual-adjustments")).with(account(storeA, Permission.INVENTORY_MOVE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"warehouseId": "%s", "reason": "start",
                                 "lines": [{"stockItemId": "%s", "quantity": 10, "unitCost": 3}]}"""
                                .formatted(warehouseA, item)))
                .andExpect(status().isCreated());
    }

    @Test
    void shippedLowersTheSourceAndReceivedRaisesTheDestinationByWhatArrivedWithAShortage() throws Exception {
        UUID transfer = create(warehouseA, warehouseB, "4", managerOf(storeA));

        command(transfer, "ship", managerOf(storeA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.lines[0].unitCost").value(3.0));
        assertThat(balance(warehouseA)).isEqualTo("6.000000|18.0000");
        assertThat(balance(warehouseB)).isNull();

        receive(transfer, "3", managerOf(storeB)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.lines[0].quantityReceived").value(3.0))
                .andExpect(jsonPath("$.lines[0].shortfall").value(1.0));

        assertThat(balance(warehouseB)).isEqualTo("3.000000|9.0000");
        assertThat(movements(transfer)).containsExactly(
                "TRANSFER_OUT|" + warehouseA + "|-4.000000|3.000000|-12.0000",
                "TRANSFER_IN|" + warehouseB + "|4.000000|3.000000|12.0000",
                "TRANSFER_SHORTAGE|" + warehouseB + "|-1.000000|3.000000|-3.0000");
    }

    @Test
    void eachEndIsAuthorizedInItsOwnStore() throws Exception {
        newTransfer(warehouseA, warehouseB, "1", managerOf(storeB)).andExpect(status().isForbidden());
        UUID transfer = create(warehouseA, warehouseB, "1", managerOf(storeA));

        command(transfer, "ship", managerOf(storeB)).andExpect(status().isForbidden());
        command(transfer, "ship", managerOf(storeA)).andExpect(status().isOk());
        receive(transfer, "1", managerOf(storeA)).andExpect(status().isForbidden());
        receive(transfer, "1", account(storeB, Permission.INVENTORY_VIEW)).andExpect(status().isForbidden());
        receive(transfer, "1", managerOf(storeB)).andExpect(status().isOk());

        mvc.perform(get(BO + "/transfers/" + transfer).with(account(storeB, Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk());
        mvc.perform(get(BO + "/transfers/" + transfer).with(account(UUID.randomUUID(), Permission.INVENTORY_VIEW)))
                .andExpect(status().isForbidden());
        command(UUID.randomUUID(), "ship", managerOf(storeA)).andExpect(status().isNotFound());
    }

    @Test
    void receivingMoreThanShippedOrTwiceIsRefused() throws Exception {
        UUID transfer = create(warehouseA, warehouseB, "2", managerOf(storeA));
        receive(transfer, "2", managerOf(storeB)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_STATE"));
        command(transfer, "ship", managerOf(storeA)).andExpect(status().isOk());

        receive(transfer, "2.5", managerOf(storeB)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RECEIVED_EXCEEDS_SHIPPED"));
        receive(transfer, "2", managerOf(storeB)).andExpect(status().isOk());
        receive(transfer, "2", managerOf(storeB)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_STATE"));
        command(transfer, "ship", managerOf(storeA)).andExpect(status().isConflict());
        assertThat(balance(warehouseB)).isEqualTo("2.000000|6.0000");
    }

    @Test
    void withinOneStoreShippingReceivesEverythingAtTheSameCost() throws Exception {
        UUID bar = fixtures.newWarehouse(storeA, "ALLOW");
        UUID transfer = create(warehouseA, bar, "2", managerOf(storeA));

        command(transfer, "ship", managerOf(storeA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.lines[0].shortfall").value(0.0));

        assertThat(balance(warehouseA)).isEqualTo("8.000000|24.0000");
        assertThat(balance(bar)).isEqualTo("2.000000|6.0000");
    }

    @Test
    void aDraftIsEditedWithItsVersionCancelledOnceAndNeverToItsOwnWarehouse() throws Exception {
        newTransfer(warehouseA, warehouseA, "1", managerOf(storeA)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SAME_WAREHOUSE"));
        UUID transfer = create(warehouseA, warehouseB, "1", managerOf(storeA));
        long version = InventoryFixtures.json(mvc.perform(get(BO + "/transfers/" + transfer)
                .with(managerOf(storeA))).andReturn().getResponse().getContentAsString()).get("version").asLong();

        mvc.perform(put(BO + "/transfers/" + transfer).with(managerOf(storeA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(editBody("5", version)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].quantityShipped").value(5.0));
        mvc.perform(put(BO + "/transfers/" + transfer).with(managerOf(storeA)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(editBody("6", version)))
                .andExpect(status().isConflict());
        command(transfer, "cancel", managerOf(storeA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        command(transfer, "cancel", managerOf(storeA)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_STATE"));
        assertThat(balance(warehouseA)).isEqualTo("10.000000|30.0000");
    }

    @Test
    void aRefusingSourceRefusesToShipBelowZero() throws Exception {
        UUID strict = fixtures.newWarehouse(storeA, "REFUSE_MANUAL");
        UUID transfer = create(strict, warehouseB, "1", managerOf(storeA));

        command(transfer, "ship", managerOf(storeA)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NEGATIVE_STOCK_REFUSED"));
        mvc.perform(get(BO + "/transfers/" + transfer).with(account(storeA, Permission.INVENTORY_VIEW)))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    private UUID create(UUID from, UUID to, String quantity, RequestPostProcessor actor) throws Exception {
        String body = newTransfer(from, to, quantity, actor).andReturn().getResponse().getContentAsString();
        return body.contains("\"id\"") ? id(body) : null;
    }

    private ResultActions newTransfer(UUID from, UUID to, String quantity, RequestPostProcessor actor)
            throws Exception {
        return mvc.perform(idem(post(BO + "/transfers")).with(actor).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromWarehouseId": "%s", "toWarehouseId": "%s",
                         "lines": [{"stockItemId": "%s", "quantity": %s}]}""".formatted(from, to, item, quantity)));
    }

    private String editBody(String quantity, long version) {
        return """
                {"lines": [{"stockItemId": "%s", "quantity": %s}], "notes": "more", "version": %d}"""
                .formatted(item, quantity, version);
    }

    private ResultActions command(UUID transfer, String command, RequestPostProcessor actor) throws Exception {
        return mvc.perform(idem(post(BO + "/transfers/" + transfer + "/" + command)).with(actor));
    }

    private ResultActions receive(UUID transfer, String quantity, RequestPostProcessor actor) throws Exception {
        return mvc.perform(idem(post(BO + "/transfers/" + transfer + "/receive")).with(actor)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"lines": [{"stockItemId": "%s", "quantityReceived": %s}]}""".formatted(item, quantity)));
    }

    private static RequestPostProcessor managerOf(UUID store) {
        return account(store, Permission.INVENTORY_TRANSFER, Permission.INVENTORY_VIEW);
    }

    private String balance(UUID warehouse) {
        return jdbc.sql("""
                        SELECT quantity || '|' || value FROM inventory.stock_balance
                        WHERE warehouse_id = ? AND stock_item_id = ?""")
                .params(warehouse, item).query(String.class).optional().orElse(null);
    }

    private List<String> movements(UUID transfer) {
        return jdbc.sql("""
                        SELECT type || '|' || warehouse_id || '|' || quantity || '|' || unit_cost || '|' || value
                        FROM inventory.stock_movement WHERE source_type = 'TRANSFER' AND source_id = ?
                        ORDER BY posting_seq""")
                .param(transfer).query(String.class).list();
    }
}
