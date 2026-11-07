package com.xavier.backend.promotions.model;

/** The line-level promotion types of R4 (P43). */
public enum PromotionKind {
    /** {@code percent} off the list unit price, rounded HALF_UP per unit. */
    PERCENT_OFF,
    /** {@code amount} off each unit, never below 0. */
    AMOUNT_OFF_UNIT,
    /** Each unit at {@code amount}; ignored where it is above the list price. */
    FIXED_UNIT_PRICE
}
