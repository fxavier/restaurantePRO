package com.xavier.backend.cash.exception;

/**
 * The stable {@code code} of a cash 422 (PRD R2 conventions): clients switch on it, so a constant is never renamed.
 */
public enum CashRule {
    /** The store's cash day for now is not OPEN (closed operationally or financially). */
    DAY_NOT_OPEN,
    /**
     * The day takes corrections only (P25, P28): an earlier day once a later one exists, or a reopened day, opens no
     * new session.
     */
    DAY_CLOSING_ONLY,
    /** The day is FINANCIALLY_CLOSED: nothing more is counted, adjusted or closed in it. */
    DAY_FINANCIALLY_CLOSED,
    /** A movement or receipt into a session that is not OPEN. */
    SESSION_NOT_OPEN,
    /**
     * A session is still OPEN: the register cannot be closed for the day, and the session takes a normal movement,
     * not an adjustment.
     */
    SESSION_STILL_OPEN,
    /** The register had no session on that day: there is nothing to close. */
    NO_SESSIONS,
    /** The register already has a live closing for the day, so no new session opens on it. */
    REGISTER_CLOSED_FOR_DAY,
    /** The register is deactivated. */
    REGISTER_INACTIVE,
    /** A register with an OPEN session cannot be deactivated. */
    REGISTER_IN_USE,
    /** The day still has OPEN sessions (operational close). */
    OPEN_SESSIONS,
    /** Not every register that worked on the day has an APPROVED closing (financial close). */
    CLOSINGS_NOT_APPROVED,
    /** A closing line with a difference other than zero has no justification. */
    JUSTIFICATION_REQUIRED,
    /** A justification or count names a method the closing has no line for. */
    UNKNOWN_METHOD,
    /** A payment method code is blank or longer than 30 characters. */
    INVALID_METHOD,
    /** The same payment method is counted twice. */
    DUPLICATE_METHOD,
    /** An amount is negative, zero where it must be positive, or has more decimals than the currency allows. */
    INVALID_AMOUNT,
    /** An amount is not in the store's currency (single currency, spec §8.4). */
    CURRENCY_MISMATCH,
    /** The approver of an expense is its operator (P24, D40). */
    SELF_APPROVAL
}
