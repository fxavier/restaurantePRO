package com.xavier.backend.inventory.controller;

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
 * Manual movements (D117, D118): waste, manual adjustments and returns to stock, {@code INVENTORY_MOVE} in the
 * warehouse's store, always with a reason, quantities converted into the item's base unit, audited, idempotent.
 */
@InventoryModuleTest
class ManualMovementHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    UUID store;
    UUID warehouse;
    UUID flour;

    @BeforeEach
    void tenKilosOfFlourAt2() throws Exception {
        store = fixtures.newActiveStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        flour = fixtures.newStockItem("kg");
        move("manual-adjustments", line(flour, "10", null, "2.00"), "opening by hand").andExpect(status().isCreated());
    }

    @Test
    void wasteIsAnOutAtTheAverageInTheBaseUnitWithItsReason() throws Exception {
        move("waste", line(flour, "500", "g", null), "dropped").andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("MANUAL_MOVEMENT"))
                .andExpect(jsonPath("$.movements[0].type").value("WASTE"))
                .andExpect(jsonPath("$.movements[0].quantity").value(-0.5))
                .andExpect(jsonPath("$.movements[0].unitCost").value(2.0))
                .andExpect(jsonPath("$.movements[0].value").value(-1.0))
                .andExpect(jsonPath("$.movements[0].reason").value("dropped"))
                .andExpect(jsonPath("$.movements[0].actorKind").value("ACCOUNT"));

        assertThat(balance()).isEqualTo("9.500000|19.0000");
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_event WHERE action = 'STOCK_WASTE_RECORDED' "
                + "AND store_id = ? AND reason = 'dropped'").param(store).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aReturnToStockComesBackAtTheAverage() throws Exception {
        move("returns-to-stock", line(flour, "1", null, "99"), "unopened bag").andExpect(status().isCreated())
                .andExpect(jsonPath("$.movements[0].type").value("RETURN_TO_STOCK"))
                .andExpect(jsonPath("$.movements[0].unitCost").value(2.0));
        assertThat(balance()).isEqualTo("11.000000|22.0000");
    }

    @Test
    void anIncomingAdjustmentNeedsAUnitCostAndAnOutgoingOneIsAtTheAverage() throws Exception {
        move("manual-adjustments", line(flour, "1", null, null), "found").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNIT_COST_REQUIRED"));
        move("manual-adjustments", line(flour, "-2", null, "50"), "spilled").andExpect(status().isCreated())
                .andExpect(jsonPath("$.movements[0].type").value("MANUAL_ADJUSTMENT"))
                .andExpect(jsonPath("$.movements[0].unitCost").value(2.0));
        assertThat(balance()).isEqualTo("8.000000|16.0000");
    }

    @Test
    void theRequestIsCheckedBeforeAnythingIsPosted() throws Exception {
        move("waste", line(flour, "1", null, null), " ").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        move("waste", line(flour, "1", null, null), null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REASON_REQUIRED"));
        move("waste", line(flour, "-1", null, null), "why").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
        move("manual-adjustments", line(flour, "0", null, null), "why").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_QUANTITY"));
        move("waste", line(flour, "1", "l", null), "why").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNIT_DIMENSION_MISMATCH"));
        move("waste", line(flour, "1", null, null) + ", " + line(flour, "2", null, null), "why")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DUPLICATE_STOCK_ITEM"));
        move("waste", line(UUID.randomUUID(), "1", null, null), "why").andExpect(status().isNotFound());
        move("waste", "", "why").andExpect(status().isBadRequest());

        assertThat(balance()).isEqualTo("10.000000|20.0000");
    }

    /** D122: before go-live nothing is moved by hand (the opening count comes first, S49). */
    @Test
    void aStoreWithoutActivationRefusesManualMovements() throws Exception {
        UUID inactive = fixtures.newStore();
        UUID room = fixtures.newWarehouse(inactive, "ALLOW");
        for (String path : java.util.List.of("waste", "manual-adjustments", "returns-to-stock")) {
            mvc.perform(idem(post(BO + "/movements/" + path)).with(account(inactive, Permission.INVENTORY_MOVE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(room, line(flour, "1", null, "1"), "why")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("INVENTORY_NOT_ACTIVE"));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.stock_movement WHERE warehouse_id = ?").param(room)
                .query(Long.class).single()).isZero();
    }

    @Test
    void movingNeedsInventoryMoveInTheWarehousesStore() throws Exception {
        move("waste", line(flour, "1", null, null), "why", account(UUID.randomUUID(), Permission.INVENTORY_MOVE))
                .andExpect(status().isForbidden());
        move("waste", line(flour, "1", null, null), "why", account(store, Permission.INVENTORY_MANAGE))
                .andExpect(status().isForbidden());
        mvc.perform(idem(post(BO + "/movements/waste")).with(mover()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), line(flour, "1", null, null), "why")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aReplayedCommandPostsOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            mvc.perform(idem(post(BO + "/movements/waste"), key).with(mover())
                            .contentType(MediaType.APPLICATION_JSON).content(body(warehouse, line(flour, "1", null,
                                    null), "twice?")))
                    .andExpect(status().isCreated());
        }
        assertThat(balance()).isEqualTo("9.000000|18.0000");
    }

    private ResultActions move(String path, String lines, String reason) throws Exception {
        return move(path, lines, reason, mover());
    }

    private ResultActions move(String path, String lines, String reason, RequestPostProcessor actor)
            throws Exception {
        return mvc.perform(idem(post(BO + "/movements/" + path)).with(actor).contentType(MediaType.APPLICATION_JSON)
                .content(body(warehouse, lines, reason)));
    }

    private static String body(UUID warehouseId, String lines, String reason) {
        return """
                {"warehouseId": "%s", "lines": [%s], "reason": %s}""".formatted(warehouseId, lines,
                reason == null ? "null" : "\"" + reason + "\"");
    }

    static String line(UUID item, String quantity, String unit, String unitCost) {
        return """
                {"stockItemId": "%s", "quantity": %s, "unit": %s, "unitCost": %s}""".formatted(item, quantity,
                unit == null ? "null" : "\"" + unit + "\"", unitCost == null ? "null" : unitCost);
    }

    private RequestPostProcessor mover() {
        return account(store, Permission.INVENTORY_MOVE);
    }

    private String balance() {
        return jdbc.sql("""
                        SELECT quantity || '|' || value FROM inventory.stock_balance
                        WHERE warehouse_id = ? AND stock_item_id = ?""")
                .params(warehouse, flour).query(String.class).single();
    }
}
