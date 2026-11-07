package com.xavier.backend.inventory.purchasing.model;

/** §8.3 machine of a purchase order (D124): {@code DRAFT → SUBMITTED → CLOSED}; DRAFT or SUBMITTED → CANCELLED. */
public enum PurchaseOrderStatus {
    DRAFT, SUBMITTED, CLOSED, CANCELLED
}
