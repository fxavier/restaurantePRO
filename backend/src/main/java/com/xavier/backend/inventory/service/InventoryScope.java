package com.xavier.backend.inventory.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.repository.WarehouseRepository;
import com.xavier.backend.organization.OrganizationApi;
import com.xavier.backend.shared.persistence.StoreProbe;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Store-scope checks in the D42 order: a store taken from the request is authorized before anything is read (and
 * then must exist, 404); a warehouse is found by its store id alone (404), then authorized (403), before any lock or
 * full load. Under row-level security a resource's store is probed across every store ({@link StoreProbe}, D168), so
 * another store's resource still answers 403, not 404; warehouses are read directly, every scope reads them.
 */
@Service
public class InventoryScope {

    private final AccessPolicy accessPolicy;
    private final WarehouseRepository warehouses;
    private final OrganizationApi organization;
    private final StoreProbe probe;

    InventoryScope(AccessPolicy accessPolicy, WarehouseRepository warehouses, OrganizationApi organization,
                   StoreProbe probe) {
        this.accessPolicy = accessPolicy;
        this.warehouses = warehouses;
        this.organization = organization;
        this.probe = probe;
    }

    /** {@code permission} in {@code storeId}, which must then exist. */
    public void requireStore(Permission permission, UUID storeId) {
        accessPolicy.require(permission, storeId);
        if (!organization.storeExists(storeId)) {
            throw new InventoryNotFoundException("Store", storeId);
        }
    }

    /** {@code permission} in the store of {@code warehouseId}; returns that store. */
    public UUID requireWarehouse(Permission permission, UUID warehouseId) {
        UUID storeId = storeOfWarehouse(warehouseId);
        accessPolicy.require(permission, storeId);
        return storeId;
    }

    public UUID storeOfWarehouse(UUID warehouseId) {
        return warehouses.storeOf(warehouseId)
                .orElseThrow(() -> new InventoryNotFoundException(WarehouseService.ENTITY, warehouseId));
    }

    /**
     * D42 (b): the store of row {@code id} of {@code table} (an inventory table with a {@code store_id}), whatever the
     * caller's store scope; 404 as {@code entity} when there is no such row.
     */
    public UUID storeOf(String table, String entity, UUID id) {
        return probe.storeOf(table, id).orElseThrow(() -> new InventoryNotFoundException(entity, id));
    }
}
