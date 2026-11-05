package com.xavier.backend.production.model;

/** What a production ticket prints (r2-decisions §4): a round, a void, a subtotal, a document, a test page. */
public enum TicketSource {
    BATCH,
    VOID,
    SUBTOTAL,
    DOCUMENT,
    TEST
}
