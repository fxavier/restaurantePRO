package com.xavier.backend.inventory.preparation.model;

/** D121: DRAFT → CONFIRMED (posts the movements) or DRAFT → CANCELLED. */
public enum PreparationStatus {
    DRAFT,
    CONFIRMED,
    CANCELLED
}
