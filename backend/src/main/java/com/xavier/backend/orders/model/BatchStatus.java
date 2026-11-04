package com.xavier.backend.orders.model;

/** OrderBatch (spec §8.3): CONFIRMED → DISPATCHED, when production has received the round (D35). */
public enum BatchStatus {
    CONFIRMED,
    DISPATCHED
}
