package com.xavier.backend.orders.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.UUID;

/**
 * An order business rule refuses the request (422): the order is frozen for its invoice ({@code ORDER_FROZEN}, D49),
 * its table is locked ({@code TABLE_LOCKED}), the item or variant is not in the published catalog
 * ({@code ITEM_NOT_IN_CATALOG}), its tax category has no rate in effect at confirmation ({@code NO_TAX_RATE_IN_EFFECT},
 * D58), or a DINE_IN order was asked for without a table ({@code DINE_IN_REQUIRES_TABLE}).
 */
public class OrderRuleException extends BusinessRuleException {

    private OrderRuleException(String code, String detail) {
        super(code, detail);
    }

    public static OrderRuleException frozen(UUID orderId) {
        return new OrderRuleException("ORDER_FROZEN", "Order " + orderId + " is frozen for its invoice.");
    }

    public static OrderRuleException tableLocked(UUID tableId) {
        return new OrderRuleException("TABLE_LOCKED", "Table " + tableId + " is locked.");
    }

    public static OrderRuleException itemNotInCatalog(UUID itemId, UUID variantId) {
        return new OrderRuleException("ITEM_NOT_IN_CATALOG", "Item " + itemId
                + (variantId == null ? "" : " variant " + variantId) + " is not in the published catalog.");
    }

    public static OrderRuleException noTaxRateInEffect(String itemCode) {
        return new OrderRuleException("NO_TAX_RATE_IN_EFFECT",
                "Item " + itemCode + " has no tax rate in effect now.");
    }

    public static OrderRuleException dineInRequiresTable() {
        return new OrderRuleException("DINE_IN_REQUIRES_TABLE", "A DINE_IN order is opened by opening its table.");
    }
}
