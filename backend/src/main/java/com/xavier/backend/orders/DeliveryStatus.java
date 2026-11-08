package com.xavier.backend.orders;

/**
 * The status of a delivery (D127): PENDING → OUT_FOR_DELIVERY → DELIVERED; OUT_FOR_DELIVERY → RETURNED →
 * OUT_FOR_DELIVERY (a retry); PENDING or RETURNED → CANCELLED, with its order. Independent of the order's lifecycle,
 * billing lock and fiscal state (§8.3).
 */
public enum DeliveryStatus {
    PENDING,
    OUT_FOR_DELIVERY,
    DELIVERED,
    RETURNED,
    CANCELLED
}
