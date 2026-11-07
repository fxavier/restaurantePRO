package com.xavier.backend.orders;

/**
 * How an order is served (P15). Only DINE_IN sits at a table, through a dining occupation. R4: a DELIVERY order has a
 * customer and a {@code Delivery} (D127).
 */
public enum OrderMode {
    DINE_IN,
    COUNTER,
    TAKEAWAY,
    DELIVERY
}
