package com.xavier.backend.inventory.controller;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.code;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Warehouses, consumption routes and stock levels (D114, D118) belong to a store: {@code INVENTORY_MANAGE} in that
 * store to write, {@code INVENTORY_VIEW} to read (D42: the store of a body is checked before anything is read; a
 * resource addressed by id is found by its store id first, 404, then 403).
 */
@InventoryModuleTest
class WarehouseHttpTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    UUID store;

    @BeforeEach
    void aStore() throws Exception {
        store = fixtures.newStore();
    }

    @Test
    void aStoreManagerCreatesAWarehouseThatAllowsNegativeStockByDefault() throws Exception {
        String code = code("BAR");
        UUID warehouse = id(createWarehouse(store, code, null, account(store, Permission.INVENTORY_MANAGE))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeId").value(store.toString()))
                .andExpect(jsonPath("$.negativePolicy").value("ALLOW"))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString());

        mvc.perform(get(BO + "/warehouses/" + warehouse).with(account(store, Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(code));
        mvc.perform(get(BO + "/warehouses").param("storeId", store.toString())
                        .with(account(store, Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(warehouse.toString()));
    }

    @Test
    void anotherStoresManagerIsRefusedBeforeAnythingIsRead() throws Exception {
        UUID other = UUID.randomUUID();
        createWarehouse(store, code("W"), null, account(other, Permission.INVENTORY_MANAGE))
                .andExpect(status().isForbidden());
        createWarehouse(UUID.randomUUID(), code("W"), null, account(null, Permission.INVENTORY_MANAGE))
                .andExpect(status().isNotFound());

        UUID warehouse = fixtures.newWarehouse(store, "ALLOW");
        mvc.perform(get(BO + "/warehouses/" + warehouse).with(account(other, Permission.INVENTORY_VIEW)))
                .andExpect(status().isForbidden());
        mvc.perform(get(BO + "/warehouses/" + UUID.randomUUID()).with(account(other, Permission.INVENTORY_VIEW)))
                .andExpect(status().isNotFound());
        mvc.perform(get(BO + "/warehouses").param("storeId", store.toString())
                        .with(account(other, Permission.INVENTORY_VIEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    void aCodeIsUniquePerStore() throws Exception {
        String code = code("DUP");
        createWarehouse(store, code, null, manager()).andExpect(status().isCreated());

        createWarehouse(store, code, null, manager()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_CODE_TAKEN"));
        createWarehouse(fixtures.newStore(), code, null, account(null, Permission.INVENTORY_MANAGE))
                .andExpect(status().isCreated());
    }

    @Test
    void theNegativePolicyIsAVersionedEdit() throws Exception {
        UUID warehouse = fixtures.newWarehouse(store, "ALLOW");

        updateWarehouse(warehouse, "REFUSE_MANUAL", 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.negativePolicy").value("REFUSE_MANUAL"))
                .andExpect(jsonPath("$.version").value(1));
        updateWarehouse(warehouse, "ALLOW", 0).andExpect(status().isConflict());
        updateWarehouse(warehouse, "SOMETIMES", 1).andExpect(status().isBadRequest());
    }

    @Test
    void aRouteSendsADestinationToAWarehouseOfItsStore() throws Exception {
        UUID bar = fixtures.newWarehouse(store, "ALLOW");
        UUID kitchen = fixtures.newWarehouse(store, "ALLOW");
        UUID elsewhere = fixtures.newWarehouse(fixtures.newStore(), "ALLOW");

        createRoute(store, null, kitchen).andExpect(status().isCreated())
                .andExpect(jsonPath("$.destinationCode").doesNotExist());
        UUID route = id(createRoute(store, "BAR", bar).andExpect(status().isCreated())
                .andExpect(jsonPath("$.destinationCode").value("BAR"))
                .andReturn().getResponse().getContentAsString());

        createRoute(store, null, bar).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROUTE_EXISTS"));
        createRoute(store, "GRILL", elsewhere).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_STORE_MISMATCH"));

        mvc.perform(put(BO + "/consumption-routes/" + route).with(manager()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\": \"%s\", \"version\": 0}".formatted(kitchen)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.warehouseId").value(kitchen.toString()));
        mvc.perform(get(BO + "/consumption-routes").param("storeId", store.toString())
                        .with(account(store, Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(delete(BO + "/consumption-routes/" + route).with(manager()).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get(BO + "/consumption-routes").param("storeId", store.toString())
                        .with(account(store, Permission.INVENTORY_VIEW)))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aMinimumIsSetOncePerItemAndWarehouseAndEditedWithItsVersion() throws Exception {
        UUID warehouse = fixtures.newWarehouse(store, "ALLOW");
        UUID item = fixtures.newStockItem("kg");

        createLevel(warehouse, item, "2.5").andExpect(status().isCreated())
                .andExpect(jsonPath("$.minQuantity").value(2.5));
        createLevel(warehouse, item, "1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_LEVEL_EXISTS"));
        createLevel(warehouse, item, "-1").andExpect(status().isBadRequest());

        mvc.perform(put(BO + "/stock-levels/" + warehouse + "/" + item).with(manager()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"minQuantity\": 4, \"version\": 0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.minQuantity").value(4.0));
        mvc.perform(get(BO + "/stock-levels").param("warehouseId", warehouse.toString())
                        .with(account(store, Permission.INVENTORY_VIEW)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].version").value(1));
        mvc.perform(delete(BO + "/stock-levels/" + warehouse + "/" + item).with(manager()).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(delete(BO + "/stock-levels/" + warehouse + "/" + item).with(manager()).with(csrf()))
                .andExpect(status().isNotFound());
        createLevel(warehouse, UUID.randomUUID(), "1").andExpect(status().isNotFound());
    }

    private ResultActions createWarehouse(UUID storeId, String code, String policy, RequestPostProcessor actor)
            throws Exception {
        return mvc.perform(idem(post(BO + "/warehouses")).with(actor).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"storeId": "%s", "code": "%s", "name": "Bar", "negativePolicy": %s}"""
                        .formatted(storeId, code, policy == null ? "null" : "\"" + policy + "\"")));
    }

    private ResultActions updateWarehouse(UUID warehouse, String policy, long version) throws Exception {
        return mvc.perform(put(BO + "/warehouses/" + warehouse).with(manager()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code": "%s", "name": "Bar", "negativePolicy": "%s", "active": true, "version": %d}"""
                        .formatted(code("W"), policy, version)));
    }

    private ResultActions createRoute(UUID storeId, String destination, UUID warehouse) throws Exception {
        return mvc.perform(idem(post(BO + "/consumption-routes")).with(manager())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"storeId": "%s", "destinationCode": %s, "warehouseId": "%s"}"""
                        .formatted(storeId, destination == null ? "null" : "\"" + destination + "\"", warehouse)));
    }

    private ResultActions createLevel(UUID warehouse, UUID item, String min) throws Exception {
        return mvc.perform(idem(post(BO + "/stock-levels")).with(manager())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"warehouseId": "%s", "stockItemId": "%s", "minQuantity": %s}"""
                        .formatted(warehouse, item, min)));
    }

    private RequestPostProcessor manager() {
        return account(store, Permission.INVENTORY_MANAGE);
    }
}
