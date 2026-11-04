package com.xavier.backend.cash.model;

import java.util.Set;

public enum CashClosingStatus {
    DRAFT, SUBMITTED, APPROVED, REJECTED;

    /** The states that block a new closing of the same register and day (the partial unique index of V8005). */
    public static final Set<CashClosingStatus> LIVE = Set.of(DRAFT, SUBMITTED, APPROVED);
}
