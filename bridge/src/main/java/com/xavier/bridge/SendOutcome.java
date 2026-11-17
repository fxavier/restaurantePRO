package com.xavier.bridge;

/** What a send to a printer achieved (D57, D157): the names the backend's outcome route takes. */
enum SendOutcome {
    /** Every byte was written and the connection closed cleanly. */
    ACCEPTED,
    /** No connection: nothing reached the printer, so a retry cannot duplicate. */
    NOT_CONNECTED,
    /** The status probe answered "cover open": nothing was sent. */
    NOT_READY_COVER_OPEN,
    /** The status probe answered "paper end": nothing was sent. */
    NOT_READY_PAPER_OUT,
    /** The status probe answered "error": nothing was sent. */
    NOT_READY_ERROR,
    /** Connected, then the write failed or timed out: part of it may have printed. */
    INTERRUPTED_AFTER_WRITE;

    boolean nothingWritten() {
        return this != ACCEPTED && this != INTERRUPTED_AFTER_WRITE;
    }
}
