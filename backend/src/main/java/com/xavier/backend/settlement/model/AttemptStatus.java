package com.xavier.backend.settlement.model;

import java.util.Set;

/**
 * PaymentAttempt (spec §8.3): CREATED → PENDING → SUCCEEDED | FAILED | CANCELLED | UNKNOWN, and UNKNOWN → SUCCEEDED |
 * FAILED only by resolve. A timeout is UNKNOWN, never FAILED (§7.4).
 */
public enum AttemptStatus {
    CREATED,
    PENDING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN;

    /** Not final yet, or in doubt: blocks a new attempt on the order, the order close and the session close. */
    public static final Set<AttemptStatus> OPEN = Set.of(CREATED, PENDING, UNKNOWN);

    public boolean isOpen() {
        return OPEN.contains(this);
    }
}
