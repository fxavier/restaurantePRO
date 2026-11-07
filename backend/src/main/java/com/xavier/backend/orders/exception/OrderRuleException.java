package com.xavier.backend.orders.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * An order business rule refuses the request (422): the order is frozen for its invoice ({@code ORDER_FROZEN}, D49),
 * its table is locked ({@code TABLE_LOCKED}), inactive ({@code TABLE_INACTIVE}) or of another store
 * ({@code STORE_MISMATCH}; the same codes as dining's), the item or variant is not in the published catalog
 * ({@code ITEM_NOT_IN_CATALOG}), its tax category has no rate in effect at confirmation ({@code NO_TAX_RATE_IN_EFFECT},
 * D58), or a DINE_IN order was asked for without a table ({@code DINE_IN_REQUIRES_TABLE}). Adjustments (S14): a payment is
 * under way, so nothing may leave the bill ({@code ORDER_IN_SETTLEMENT}, D49), more is voided or transferred than the
 * line still holds ({@code QUANTITY_EXCEEDS_NET}), the approver is the operator ({@code SELF_APPROVAL}, D28), a cancel
 * of an order that still bills something ({@code ORDER_NOT_EMPTY}), a table move or transfer of an order onto itself
 * or without a table ({@code ORDER_HAS_NO_TABLE}, {@code SAME_ORDER}). R3: a subtotal of an order whose document
 * exists ({@code DOCUMENT_ISSUED}, P27). The codes are the {@link OrderRule} constants.
 */
public class OrderRuleException extends BusinessRuleException {

    private final OrderRule rule;

    private OrderRuleException(OrderRule rule, String detail) {
        super(rule.name(), detail);
        this.rule = rule;
    }

    public static OrderRuleException frozen(UUID orderId) {
        return new OrderRuleException(OrderRule.ORDER_FROZEN, "Order " + orderId + " is frozen for its invoice.");
    }

    public static OrderRuleException tableLocked(UUID tableId) {
        return new OrderRuleException(OrderRule.TABLE_LOCKED, "Table " + tableId + " is locked.");
    }

    public static OrderRuleException storeMismatch(UUID tableId) {
        return new OrderRuleException(OrderRule.STORE_MISMATCH, "Table " + tableId + " is not in this store.");
    }

    public static OrderRuleException tableInactive(UUID tableId) {
        return new OrderRuleException(OrderRule.TABLE_INACTIVE, "Table " + tableId + " or its area is inactive.");
    }

    public static OrderRuleException itemNotInCatalog(UUID itemId, UUID variantId) {
        return new OrderRuleException(OrderRule.ITEM_NOT_IN_CATALOG, "Item " + itemId
                + (variantId == null ? "" : " variant " + variantId) + " is not in the published catalog.");
    }

    public static OrderRuleException noTaxRateInEffect(String itemCode) {
        return new OrderRuleException(OrderRule.NO_TAX_RATE_IN_EFFECT,
                "Item " + itemCode + " has no tax rate in effect now.");
    }

    public static OrderRuleException dineInRequiresTable() {
        return new OrderRuleException(OrderRule.DINE_IN_REQUIRES_TABLE, "A DINE_IN order is opened by opening its table.");
    }

    public static OrderRuleException inSettlement(UUID orderId) {
        return new OrderRuleException(OrderRule.ORDER_IN_SETTLEMENT,
                "Order " + orderId + " has a payment under way: nothing may leave its bill.");
    }

    public static OrderRuleException quantityExceedsNet(UUID lineId, BigDecimal net) {
        return new OrderRuleException(OrderRule.QUANTITY_EXCEEDS_NET,
                "Line " + lineId + " holds only " + net.toPlainString() + ".");
    }

    public static OrderRuleException selfApproval() {
        return new OrderRuleException(OrderRule.SELF_APPROVAL, "The approver must be another employee than the operator.");
    }

    public static OrderRuleException notEmpty(UUID orderId) {
        return new OrderRuleException(OrderRule.ORDER_NOT_EMPTY, "Order " + orderId + " still bills something.");
    }

    public static OrderRuleException hasNoTable(UUID orderId) {
        return new OrderRuleException(OrderRule.ORDER_HAS_NO_TABLE, "Order " + orderId + " does not sit at a table.");
    }

    public static OrderRuleException sameOrder(UUID orderId) {
        return new OrderRuleException(OrderRule.SAME_ORDER, "Order " + orderId + " cannot transfer to itself.");
    }

    public static OrderRuleException documentIssued(UUID orderId) {
        return new OrderRuleException(OrderRule.DOCUMENT_ISSUED,
                "Order " + orderId + " has its document: reprint it as a copy instead of a subtotal.");
    }

    public static OrderRuleException itemSoldOut(UUID itemId, UUID variantId) {
        return new OrderRuleException(OrderRule.ITEM_SOLD_OUT, "Item " + itemId
                + (variantId == null ? "" : " variant " + variantId) + " is sold out.");
    }

    public static OrderRuleException menuSelectionInvalid(String itemCode, String why) {
        return new OrderRuleException(OrderRule.MENU_SELECTION_INVALID, "Menu " + itemCode + ": " + why + ".");
    }

    public static OrderRuleException macroNotPublished(String macroCode) {
        return new OrderRuleException(OrderRule.MACRO_NOT_PUBLISHED,
                "Macro " + macroCode + " is not in the published catalog.");
    }

    public static OrderRuleException discountLimitExceeded(UUID lineId, BigDecimal percent, BigDecimal maxPercent) {
        return new OrderRuleException(OrderRule.DISCOUNT_LIMIT_EXCEEDED, "Line " + lineId + " would be "
                + percent.toPlainString() + " % off its list price; the store allows at most "
                + maxPercent.toPlainString() + " %.");
    }

    public static OrderRuleException priceIncreaseNotAllowed(UUID lineId) {
        return new OrderRuleException(OrderRule.PRICE_INCREASE_NOT_ALLOWED,
                "Line " + lineId + ": a discount only lowers the price, and a reversal only restores it up to the "
                        + "list price of a line without a reward.");
    }

    public static OrderRuleException loyaltyCustomerRequired(UUID orderId) {
        return new OrderRuleException(OrderRule.LOYALTY_CUSTOMER_REQUIRED,
                "Order " + orderId + " has no customer to redeem a reward for.");
    }

    public static OrderRuleException rewardNeedsSingleUnit(UUID lineId) {
        return new OrderRuleException(OrderRule.REWARD_NEEDS_SINGLE_UNIT,
                "A reward is redeemed on one unit of line " + lineId + ".");
    }

    public static OrderRuleException loyaltyRedeemed(UUID orderId) {
        return new OrderRuleException(OrderRule.LOYALTY_REDEEMED,
                "Order " + orderId + " has a redeemed reward: its customer cannot change.");
    }

    public OrderRule rule() {
        return rule;
    }
}
