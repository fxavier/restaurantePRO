package com.xavier.backend.orders;

/** How an order is served (P15). Only DINE_IN sits at a table, through a dining occupation. */
public enum OrderMode {
    DINE_IN,
    COUNTER,
    TAKEAWAY
}
