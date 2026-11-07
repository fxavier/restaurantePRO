package com.xavier.backend.inventory.consumption.flow;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.concurrent.locks.LockSupport;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Inventory switched on in a sale's store: live from now, one warehouse as the default route, flour in
 * stock (100 kg at 2.00) and a recipe of 250 g of flour per item sold (the shop's item).
 */
record LiveStore(UUID store, UUID warehouse, UUID flour) {

    static LiveStore of(Sale sale, MockMvc mvc, InventoryFixtures fixtures) throws Exception {
        UUID store = sale.store();
        mvc.perform(idem(post(BO + "/activations")).with(account(store, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\"}".formatted(store))) // live from now
                .andExpect(status().isCreated());
        UUID warehouse = fixtures.newWarehouse(store, "ALLOW");
        mvc.perform(idem(post(BO + "/consumption-routes")).with(account(null, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\", \"warehouseId\": \"%s\"}".formatted(store, warehouse)))
                .andExpect(status().isCreated());
        UUID flour = fixtures.newStockItem("kg");
        mvc.perform(idem(post(BO + "/movements/manual-adjustments")).with(account(store, Permission.INVENTORY_MOVE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"warehouseId": "%s", "reason": "opening",
                                 "lines": [{"stockItemId": "%s", "quantity": 100, "unitCost": 2}]}"""
                                .formatted(warehouse, flour)))
                .andExpect(status().isCreated());
        UUID recipe = id(mvc.perform(idem(post(BO + "/recipes")).with(account(null, Permission.RECIPE_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\": \"%s\", \"name\": \"Shop item\"}".formatted(sale.shop().item())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(idem(post(BO + "/recipes/" + recipe + "/versions")).with(account(null, Permission.RECIPE_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"yieldQuantity": 1, "yieldUnit": "un", "ingredients": [
                                 {"stockItemId": "%s", "quantity": 250, "unit": "g", "kind": "INGREDIENT"}]}"""
                                .formatted(flour)))
                .andExpect(status().isCreated());
        mvc.perform(idem(post(BO + "/recipes/" + recipe + "/versions/1/activate"))
                        .with(account(null, Permission.RECIPE_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        return new LiveStore(store, warehouse, flour);
    }

    long movements(JdbcClient jdbc, String type) {
        return jdbc.sql("SELECT count(*) FROM inventory.stock_movement WHERE store_id = ? AND type = ?")
                .params(store, type).query(Long.class).single();
    }

    BigDecimal flourInStock(JdbcClient jdbc) {
        return jdbc.sql("SELECT quantity FROM inventory.stock_balance WHERE warehouse_id = ? AND stock_item_id = ?")
                .params(warehouse, flour).query(BigDecimal.class).single();
    }

    /** Publications of the round listener for {@code order}'s rounds, completed or not. */
    static long roundPublications(JdbcClient jdbc, UUID order, boolean completed) {
        return jdbc.sql("""
                        SELECT count(*) FROM event_publication
                        WHERE listener_id LIKE 'com.xavier.backend.inventory.consumption.RoundConsumptionListener%'
                          AND serialized_event LIKE :order AND completion_date IS {state}""".replace(
                        "{state}", completed ? "NOT NULL" : "NULL"))
                .param("order", "%" + order + "%").query(Long.class).single();
    }

    static void waitUntil(BooleanSupplier condition, String what) {
        Instant deadline = Instant.now(java.time.Clock.systemUTC()).plus(Duration.ofSeconds(60));
        while (!condition.getAsBoolean()) {
            if (Instant.now(java.time.Clock.systemUTC()).isAfter(deadline)) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        }
    }
}
