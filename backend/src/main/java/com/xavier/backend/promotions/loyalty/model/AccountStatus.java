package com.xavier.backend.promotions.loyalty.model;

/** A loyalty account is ACTIVE (earns and redeems) or SUSPENDED, with a reason (ACTIVE ↔ SUSPENDED). */
public enum AccountStatus {
    ACTIVE,
    SUSPENDED
}
