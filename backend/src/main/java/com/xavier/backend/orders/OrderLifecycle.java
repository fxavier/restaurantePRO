package com.xavier.backend.orders;

/**
 * Order.lifecycle (spec §8.3): OPEN → CLOSED (only through settlement, {@code OrdersApi.close}) or OPEN → CANCELLED.
 * Independent of the financial state, which settlement derives, and of the {@link BillingLock}.
 */
public enum OrderLifecycle {
    OPEN,
    CLOSED,
    CANCELLED
}
