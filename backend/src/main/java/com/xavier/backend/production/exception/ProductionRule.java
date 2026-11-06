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
    UNKNOWN_STORE
}
