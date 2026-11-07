package com.xavier.backend.orders;

/**
 * How a price adjustment changes a confirmed line's effective unit price (D105, D106, D112): a DISCOUNT (a percent or
 * a new price), an OFFER (price 0, always approved), a loyalty REWARD on one unit, or a REVERSAL that raises the price
 * back, at most to the list price (only while no payment is under way).
 */
public enum AdjustmentKind {
    DISCOUNT,
    OFFER,
    REWARD,
    REVERSAL
}
