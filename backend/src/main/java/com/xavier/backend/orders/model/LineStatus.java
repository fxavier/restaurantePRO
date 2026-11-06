package com.xavier.backend.orders.model;

/** OrderLine (spec §8.3): DRAFT → CONFIRMED. A CONFIRMED line is immutable; voids and transfers are new facts. */
public enum LineStatus {
    DRAFT,
    CONFIRMED
}
