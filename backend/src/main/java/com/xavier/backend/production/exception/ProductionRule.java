package com.xavier.backend.production.exception;

/** The stable {@code code}s of production's 422 responses (PRD R2 conventions). Never rename one. */
public enum ProductionRule {
    /** REDIRECT without another printer of the same store, to a redirecting printer, or forming a chain. */
    PRINTER_REDIRECT_INVALID,
    /** A route or terminal names a printer that is not in the store. */
    PRINTER_NOT_IN_STORE,
    /** A destination or terminal appears twice in one routing table. */
    DUPLICATE_ROUTE,
    /** The printer a new job would go to is in IGNORE. */
    PRINTER_IGNORED,
    /** A document copy is a fiscal reprint (spec §8.4), never a production reprint. */
    DOCUMENT_REPRINT_THROUGH_FISCAL,
    /** The store does not exist. */
    UNKNOWN_STORE,
    /** An UNROUTED ticket still has no route or terminal printer to go to (D89 (1)). */
    STILL_UNROUTED,
    /** A printer profile outside D154's ranges: columns for the paper width, drawer pulse, table, feed or pin. */
    PRINTER_PROFILE_INVALID,
    /** The manual drawer kick (D158) needs a reason. */
    REASON_REQUIRED,
    /** The session's terminal has no receipt printer, so no drawer to open (D158). */
    NO_RECEIPT_PRINTER,
    /** The terminal's receipt printer has no cash drawer ({@code drawer_pin} unset, D158). */
    NO_CASH_DRAWER
}
