package com.xavier.backend.inventory.controller;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.InventoryModuleTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Stock, movements and status of a store (D113, D116, D118, D122): {@code INVENTORY_VIEW} in the store; listings
 * are pages (D23). Stock can be filtered to the items below their minimum, or below zero.
 */
@InventoryModuleTest
class StockQueryHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    UUID store;
    UUID warehouse;
    UUID plenty;
    UUID scarce;
    UUID negative;

    @BeforeEach
    void threeItems() throws Exception {
        store = fixtures.newStore();
        warehouse = fixtures.newWarehouse(store, "ALLOW");
        plenty = fixtures.newStockItem("un");
        scarce = fixtures.newStockItem("un");
        negative = fixtures.newStockItem("un");
        adjust(plenty, "10");
        adjust(scarce, "1");
        mvc.perform(idem(post(BO + "/movements/waste")).with(mover()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(negative, "2", "broken")))
                .andExpect(status().isCreated());
        for (UUID item : new UUID[] {plenty, scarce}) {
            mvc.perform(idem(post(BO + "/stock-levels")).with(account(store, Permission.INVENTORY_MANAGE))
                            .contentType(MediaType.APPLICATION_JSON).content("""
                                    {"warehouseId": "%s", "stockItemId": "%s", "minQuantity": 5}"""
                                    .formatted(warehouse, item)))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void stockListsWhatTheWarehouseHoldsAndFiltersBelowMinimumOrNegative() throws Exception {
        mvc.perform(get(BO + "/stock").param("warehouseId", warehouse.toString()).with(viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[?(@.stockItemId == '%s')].quantity".formatted(plenty)).value(10.0))
                .andExpect(jsonPath("$.content[?(@.stockItemId == '%s')].averageCost".formatted(plenty)).value(2.0))
                .andExpect(jsonPath("$.content[?(@.stockItemId == '%s')].belowMinimum".formatted(scarce))
                        .value(true));
        mvc.perform(get(BO + "/stock").param("warehouseId", warehouse.toString()).param("belowMinimum", "true")
                        .with(viewer()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].stockItemId").value(scarce.toString()));
        mvc.perform(get(BO + "/stock").param("warehouseId", warehouse.toString()).param("negative", "true")
                        .with(viewer()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].stockItemId").value(negative.toString()))
                .andExpect(jsonPath("$.content[0].quantity").value(-2.0));
    }

    @Test
    void movementsArePagedNewestFirstAndFilteredByItemTypeAndDay() throws Exception {
        mvc.perform(get(BO + "/movements").param("warehouseId", warehouse.toString()).param("size", "2")
                        .with(viewer()))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].type").value("WASTE"));
        mvc.perform(get(BO + "/movements").param("warehouseId", warehouse.toString())
                        .param("stockItemId", plenty.toString()).with(viewer()))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(BO + "/movements").param("warehouseId", warehouse.toString()).param("type", "WASTE")
                        .with(viewer()))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(BO + "/movements").param("warehouseId", warehouse.toString()).param("from", "2000-01-01")
                        .param("to", "2000-01-02").with(viewer()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(BO + "/movements").param("warehouseId", warehouse.toString()).param("from", "2000-01-02")
                        .param("to", "2000-01-01").with(viewer()))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
    }

    @Test
    void theStatusShowsActivationNegativesExceptionsAndDrift() throws Exception {
        mvc.perform(get(BO + "/status").param("storeId", store.toString()).with(viewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("INVENTORY_NOT_ACTIVE"))
                .andExpect(jsonPath("$.negativeBalances").value(1))
                .andExpect(jsonPath("$.openExceptions").value(0))
                .andExpect(jsonPath("$.driftingBalances").value(0))
                .andExpect(jsonPath("$.pendingFacts").isNumber());

        jdbc.sql("""
                        INSERT INTO inventory.consumption_exception (id, store_id, source_type, source_id, kind, created_at)
                        VALUES (gen_random_uuid(), ?, 'ORDER_LINE', gen_random_uuid(), 'NO_RECIPE', now())""")
                .param(store).update();
        mvc.perform(idem(post(BO + "/activations")).with(account(store, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\", \"activeFrom\": \"%s\"}".formatted(store,
                                Instant.parse("2026-11-01T05:00:00Z"))))
                .andExpect(status().isCreated());

        mvc.perform(get(BO + "/status").param("storeId", store.toString()).with(viewer()))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.activeFrom").value("2026-11-01T05:00:00Z"))
                .andExpect(jsonPath("$.openExceptions").value(1));
    }

    @Test
    void readingNeedsInventoryViewInTheStore() throws Exception {
        RequestPostProcessor elsewhere = account(UUID.randomUUID(), Permission.INVENTORY_VIEW);
        mvc.perform(get(BO + "/stock").param("warehouseId", warehouse.toString()).with(elsewhere))
                .andExpect(status().isForbidden());
        mvc.perform(get(BO + "/movements").param("warehouseId", warehouse.toString()).with(elsewhere))
                .andExpect(status().isForbidden());
        mvc.perform(get(BO + "/status").param("storeId", store.toString()).with(elsewhere))
                .andExpect(status().isForbidden());
        mvc.perform(get(BO + "/stock").param("warehouseId", UUID.randomUUID().toString()).with(viewer()))
                .andExpect(status().isNotFound());
    }

    private void adjust(UUID item, String quantity) throws Exception {
        mvc.perform(idem(post(BO + "/movements/manual-adjustments")).with(mover())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"warehouseId": "%s", "reason": "start",
                                 "lines": [{"stockItemId": "%s", "quantity": %s, "unitCost": 2}]}"""
                                .formatted(warehouse, item, quantity)))
                .andExpect(status().isCreated());
    }

    private String body(UUID item, String quantity, String reason) {
        return """
                {"warehouseId": "%s", "reason": "%s", "lines": [{"stockItemId": "%s", "quantity": %s}]}"""
                .formatted(warehouse, reason, item, quantity);
    }

    private RequestPostProcessor mover() {
        return account(store, Permission.INVENTORY_MOVE);
    }

    private RequestPostProcessor viewer() {
        return account(store, Permission.INVENTORY_VIEW);
    }
}
