package com.xavier.backend.inventory.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** An inventory state conflict: 409 with a stable {@code code} (PRD R4 conventions). */
public class InventoryConflictException extends ConflictException {

    public InventoryConflictException(String code, String message) {
        super(code, message);
    }

    /** A transfer command its state does not allow (D120). */
    public static InventoryConflictException transferState(UUID transferId, Object status, String action) {
        return new InventoryConflictException("TRANSFER_STATE",
                "Transfer " + transferId + " is " + status + " and cannot " + action + ".");
    }

    public static InventoryConflictException alreadyActive(UUID storeId) {
        return new InventoryConflictException("INVENTORY_ALREADY_ACTIVE",
                "Inventory is already active in store " + storeId + ".");
    }

    /**
     * A source posted again with other values than its recorded movement (a redelivery must repeat it exactly).
     * Nothing is posted.
     */
    public static InventoryConflictException sourceMismatch(String sourceType, UUID sourceId) {
        return new InventoryConflictException("MOVEMENT_SOURCE_MISMATCH", "The " + sourceType + " " + sourceId
                + " already has stock movements with other values.");
    }

    public static InventoryConflictException codeTaken(String entity, String code) {
        return new InventoryConflictException(entity.toUpperCase() + "_CODE_TAKEN",
                "The " + entity.toLowerCase().replace('_', ' ') + " code " + code + " is taken.");
    }

    public static InventoryConflictException routeExists(UUID storeId, String destination) {
        return new InventoryConflictException("ROUTE_EXISTS", "Store " + storeId + " already has a "
                + (destination == null ? "default route" : "route for destination " + destination) + ".");
    }

    public static InventoryConflictException levelExists(UUID warehouseId, UUID stockItemId) {
        return new InventoryConflictException("STOCK_LEVEL_EXISTS", "Warehouse " + warehouseId
                + " already has a minimum for stock item " + stockItemId + ".");
    }
}
