package com.xavier.backend.inventory.model;

/** {@code DRAFT → SHIPPED → RECEIVED}; {@code DRAFT → CANCELLED} (D120). In transit = SHIPPED. */
public enum TransferStatus {
    DRAFT,
    SHIPPED,
    RECEIVED,
    CANCELLED
}
