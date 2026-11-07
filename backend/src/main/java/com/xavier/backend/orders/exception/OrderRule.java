package com.xavier.backend.orders.exception;

/** The stable {@code code}s of orders' 422 responses (PRD R2 conventions): clients switch on them. Never rename one. */
public enum OrderRule {
    /** The order is frozen for its invoice (billing lock INVOICE, D49): every change is refused. */
    ORDER_FROZEN,
    /** The table is locked (the same code as dining's). */
    TABLE_LOCKED,
    /** The table is not in the caller's store (the same code as dining's). */
    STORE_MISMATCH,
    /** The table or its area is inactive (the same code as dining's). */
    TABLE_INACTIVE,
    /** The item or variant is not in the published catalog. */
    ITEM_NOT_IN_CATALOG,
    /** The item's tax category has no rate in effect at confirmation (D58). */
    NO_TAX_RATE_IN_EFFECT,
    /** A DINE_IN order is opened by opening its table, never on its own. */
    DINE_IN_REQUIRES_TABLE,
    /** A payment is under way (billing lock SETTLEMENT, D49): nothing may leave the bill. */
    ORDER_IN_SETTLEMENT,
    /** More is voided or transferred than the line still holds. */
    QUANTITY_EXCEEDS_NET,
    /** The approver is the operator (D28). */
    SELF_APPROVAL,
    /** A cancel of an order that still bills something. */
    ORDER_NOT_EMPTY,
    /** A table move of an order that does not sit at a table (COUNTER, TAKEAWAY). */
    ORDER_HAS_NO_TABLE,
    /** A transfer of an order onto itself. */
    SAME_ORDER,
    /** A subtotal of an order whose document exists (P27): the POS reprints the document as a copy instead. */
    DOCUMENT_ISSUED,
    /** R4: an item (or variant) of the round, or a menu component, is flagged sold out in the store (D109). */
    ITEM_SOLD_OUT,
    /** R4: a menu's choices do not match its groups in the published catalog (D108). */
    MENU_SELECTION_INVALID,
    /** R4: no macro with that code in the published catalog (D109). */
    MACRO_NOT_PUBLISHED,
    /** R4: the cumulative discount against the list price exceeds the store's maximum (D106). */
    DISCOUNT_LIMIT_EXCEEDED,
    /** R4: a discount that does not lower the price, or a reversal above the list price or on a reward (D105). */
    PRICE_INCREASE_NOT_ALLOWED,
    /** R4: a reward on an order without a customer (D112). */
    LOYALTY_CUSTOMER_REQUIRED,
    /** R4: a reward is redeemed on exactly one unit (D112). */
    REWARD_NEEDS_SINGLE_UNIT,
    /** R4: the order's customer cannot change once a reward was redeemed on it (D112). */
    LOYALTY_REDEEMED,
    /** R4: the line already carries a reward, its own or one it was transferred or split from (D112a). */
    REWARD_ALREADY_REDEEMED
}
