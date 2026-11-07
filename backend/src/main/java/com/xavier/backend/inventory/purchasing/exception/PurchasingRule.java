package com.xavier.backend.inventory.purchasing.exception;

/** The stable {@code code} of each purchasing business rule (422, PRD R4 S48). Never renamed once released. */
public enum PurchasingRule {
    PO_HAS_RECEIPTS,
    PO_NOT_SUBMITTED,
    RETURN_EXCEEDS_RECEIVED,
    RECEIPT_NOT_CONFIRMED,
    PURCHASE_REFERENCE_MISMATCH,
    UNIT_COST_REQUIRED,
    REASON_REQUIRED,
    LINE_VALUE_TOO_LARGE
}
