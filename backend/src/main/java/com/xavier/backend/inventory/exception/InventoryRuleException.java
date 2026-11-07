package com.xavier.backend.inventory.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** An inventory business rule refused the request: 422 with the stable {@code code} (PRD R4 conventions). */
public class InventoryRuleException extends BusinessRuleException {

    private final InventoryRule rule;

    public InventoryRuleException(InventoryRule rule, String detail) {
        this(rule, detail, Map.of());
    }

    public InventoryRuleException(InventoryRule rule, String detail, Map<String, ?> properties) {
        super(rule.name(), detail, properties);
        this.rule = rule;
    }

    public static InventoryRuleException dimensionMismatch(String from, String to) {
        return new InventoryRuleException(InventoryRule.UNIT_DIMENSION_MISMATCH,
                "The unit " + from + " cannot be converted to " + to + ".");
    }

    public static InventoryRuleException unknownUnit(String unit) {
        return new InventoryRuleException(InventoryRule.UNKNOWN_UNIT, "There is no stock unit " + unit + ".");
    }

    public static InventoryRuleException warehouseStoreMismatch(UUID warehouseId, UUID storeId) {
        return new InventoryRuleException(InventoryRule.WAREHOUSE_STORE_MISMATCH,
                "Warehouse " + warehouseId + " does not belong to store " + storeId + ".");
    }

    public static InventoryRuleException reasonRequired() {
        return new InventoryRuleException(InventoryRule.REASON_REQUIRED, "A manual stock movement needs a reason.");
    }

    /** {@code available} is what the warehouse holds before the movement. */
    public static InventoryRuleException negativeStockRefused(UUID warehouseId, UUID stockItemId,
                                                              BigDecimal available) {
        return new InventoryRuleException(InventoryRule.NEGATIVE_STOCK_REFUSED, "Warehouse " + warehouseId
                + " refuses manual movements below zero: it holds " + available.toPlainString() + " of stock item "
                + stockItemId + ".", Map.of("stockItemId", stockItemId, "available", available));
    }

    public static InventoryRuleException unitCostRequired(UUID stockItemId) {
        return new InventoryRuleException(InventoryRule.UNIT_COST_REQUIRED,
                "An incoming adjustment of stock item " + stockItemId + " needs a unit cost.");
    }

    public static InventoryRuleException invalidQuantity(String detail) {
        return new InventoryRuleException(InventoryRule.INVALID_QUANTITY, detail);
    }

    public static InventoryRuleException duplicateStockItem(UUID stockItemId) {
        return new InventoryRuleException(InventoryRule.DUPLICATE_STOCK_ITEM,
                "Stock item " + stockItemId + " appears on more than one line.");
    }

    public static InventoryRuleException sameWarehouse(UUID warehouseId) {
        return new InventoryRuleException(InventoryRule.SAME_WAREHOUSE,
                "A transfer goes to another warehouse than " + warehouseId + ".");
    }

    public static InventoryRuleException receivedExceedsShipped(UUID stockItemId, BigDecimal shipped) {
        return new InventoryRuleException(InventoryRule.RECEIVED_EXCEEDS_SHIPPED, "More of stock item "
                + stockItemId + " was received than the " + shipped.toPlainString() + " shipped.",
                Map.of("stockItemId", stockItemId, "shipped", shipped));
    }

    public static InventoryRuleException invalidPeriod(Object from, Object to) {
        return new InventoryRuleException(InventoryRule.INVALID_PERIOD, "The period starts (" + from
                + ") after it ends (" + to + ").");
    }

    public static InventoryRuleException periodRequired() {
        return new InventoryRuleException(InventoryRule.INVALID_PERIOD, "This report needs a period (from, to).");
    }

    public static InventoryRuleException rangeTooLarge(long maxDays) {
        return new InventoryRuleException(InventoryRule.RANGE_TOO_LARGE,
                "The range is longer than " + maxDays + " days: narrow it.", Map.of("maxDays", maxDays));
    }

    public static InventoryRuleException notActive(UUID storeId) {
        return new InventoryRuleException(InventoryRule.INVENTORY_NOT_ACTIVE,
                "Inventory is not active in store " + storeId + ": activate it and count the opening stock first.");
    }

    public InventoryRule rule() {
        return rule;
    }
}
