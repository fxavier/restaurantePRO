package com.xavier.backend.settlement.reconciliation.model;

/**
 * The decision about a reconciliation entry (D78). {@code AUTO_MATCHED} is the system's, for a MATCHED entry at
 * import; the others are a person's, with a reason: {@code ACCEPTED} (the difference is explained, e.g. a fee),
 * {@code REFUNDED_AT_PROVIDER} (the provider gave the money back), {@code ESCALATED} (it needs someone else). A
 * resolution never corrects money: a correction is its own command (a refund, a cash adjustment).
 */
public enum ResolutionAction {
    ACCEPTED,
    REFUNDED_AT_PROVIDER,
    ESCALATED,
    AUTO_MATCHED
}
