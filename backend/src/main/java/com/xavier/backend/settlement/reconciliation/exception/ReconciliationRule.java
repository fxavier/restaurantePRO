package com.xavier.backend.settlement.reconciliation.exception;

/**
 * The stable {@code code} of every reconciliation error (PRD S31): clients switch on it, so a constant is never
 * renamed.
 */
public enum ReconciliationRule {
    /** 422: the statement or its request has errors; {@code errors} lists each {@code {line, field, message}}. */
    STATEMENT_INVALID,
    /** 422: the method's provider cannot fetch statements (no {@code StatementSource}); import its CSV instead. */
    NO_STATEMENT_SOURCE,
    /** 409: this method already has a batch with the same content; {@code batchId} is it. */
    STATEMENT_ALREADY_IMPORTED,
    /** 409: the entry already has its resolution. */
    ENTRY_RESOLVED,
    /** 503: the provider did not send its statement in time; nothing was imported, retry later. */
    STATEMENT_SOURCE_TIMEOUT,
    /** 409: the pending fiscal correction already has its resolution (D96). */
    TAX_CORRECTION_RESOLVED,
    /** 422: the tax rounding adjustment was ABSORBED by a residual document: there is nothing to correct (D96). */
    NOT_A_PENDING_CORRECTION
}
