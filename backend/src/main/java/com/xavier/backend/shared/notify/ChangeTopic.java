package com.xavier.backend.shared.notify;

/** What changed, in a POS change notice (D60). A notice carries a topic and an id, never data. */
public enum ChangeTopic {
    /** The table map of the store (open, close, move, lock). */
    TABLES,
    /** One order; the notice id is the order id. */
    ORDER,
    /** The published catalog of the store. */
    CATALOG,
    /** Print jobs of the store (failures, retries). */
    PRINT_JOBS,
    /** One payment attempt; the notice id is the attempt id. */
    PAYMENT_ATTEMPT
}
