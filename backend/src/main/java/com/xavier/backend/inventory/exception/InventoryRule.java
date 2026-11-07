package com.xavier.backend.inventory.exception;

/** The stable {@code code} of each inventory business rule (422). Never renamed once released. */
public enum InventoryRule {
    UNIT_DIMENSION_MISMATCH,
    UNKNOWN_UNIT,
    WAREHOUSE_STORE_MISMATCH,
    REASON_REQUIRED,
    NEGATIVE_STOCK_REFUSED,
    UNIT_COST_REQUIRED,
    INVALID_QUANTITY,
    DUPLICATE_STOCK_ITEM,
    SAME_WAREHOUSE,
    RECEIVED_EXCEEDS_SHIPPED,
    INVALID_PERIOD,
    RANGE_TOO_LARGE,
    INVENTORY_NOT_ACTIVE
}
