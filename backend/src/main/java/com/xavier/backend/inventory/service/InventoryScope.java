package com.xavier.backend.inventory.service;

import com.xavier.backend.access.AccessPolicy;
import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.repository.WarehouseRepository;
import com.xavier.backend.organization.OrganizationApi;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Store-scope checks in the D42 order: a store taken from the request is authorized before anything is read (and
 * then must exist, 404); a warehouse is found by its store id alone (404), then authorized (403), before any lock or
 * full load.
 */
@Service
public class InventoryScope {

    private final AccessPolicy accessPolicy;
    private final WarehouseRepository warehouses;
    private final OrganizationApi organization;

    InventoryScope(AccessPolicy accessPolicy, WarehouseRepository warehouses, OrganizationApi organization) {
        this.accessPolicy = accessPolicy;
        this.warehouses = warehouses;
        this.organization = organization;
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
}
