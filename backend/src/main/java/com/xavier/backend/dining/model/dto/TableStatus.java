package com.xavier.backend.dining.model.dto;

/**
 * Operational state of a table on the POS map (spec §8.3): OCCUPIED while it has an open TableOccupation, else
 * FREE. Locked and subtotal printed are independent indicators, not states (§8.4).
 */
public enum TableStatus {
    FREE,
    OCCUPIED
}
