package com.xavier.backend.inventory.purchasing.model;

/**
 * §8.3 machine of a goods receipt and of a supplier return (D125): {@code DRAFT → CONFIRMED} or
 * {@code DRAFT → CANCELLED}. Only CONFIRMED moves stock (§8.4).
 */
public enum PurchaseDocumentStatus {
    DRAFT, CONFIRMED, CANCELLED
}
