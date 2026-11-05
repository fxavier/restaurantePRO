package com.xavier.backend.production.model;

/**
 * What a production ticket prints (r2-decisions §4): a round, a void, a subtotal, a document, a test page, a debt
 * payment slip (D75). A round or a void prints on its production destination's printer; the others on the printer of
 * the terminal they ran on.
 */
public enum TicketSource {
    BATCH,
    VOID,
    SUBTOTAL,
    DOCUMENT,
    TEST,
    RECEIPT_SLIP;

    /** Routed by the ticket's production destination, not by its terminal. */
    public boolean routedByDestination() {
        return this == BATCH || this == VOID;
    }
}
