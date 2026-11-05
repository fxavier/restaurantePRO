package com.xavier.backend.fiscal.model.dto;

/**
 * What a document was issued for: an {@code ORDER} (R2's one SALE per order, and R3's residual document at close)
 * or an {@code ITEMS} bill {@code PARTITION} invoiced on its own (D70).
 */
public enum SourceType {
    ORDER,
    PARTITION
}
