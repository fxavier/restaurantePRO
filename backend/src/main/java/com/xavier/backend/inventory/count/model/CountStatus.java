package com.xavier.backend.inventory.count.model;

/**
 * {@code DRAFT → IN_PROGRESS → SUBMITTED → APPROVED | REJECTED}; {@code DRAFT | IN_PROGRESS → CANCELLED}. DRAFT,
 * IN_PROGRESS and SUBMITTED are live: one live count per warehouse.
 */
public enum CountStatus {
    DRAFT,
    IN_PROGRESS,
    SUBMITTED,
    APPROVED,
    REJECTED,
    CANCELLED
}
