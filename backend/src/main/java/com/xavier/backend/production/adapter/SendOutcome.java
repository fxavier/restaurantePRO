package com.xavier.backend.production.adapter;

/** What a send to a printer achieved (D57, D157). */
public enum SendOutcome {
    /** Every byte was written and the connection closed cleanly: SENT. */
    ACCEPTED,
    /** No connection: nothing reached the printer, so a retry cannot duplicate (RETRY_WAIT). */
    NOT_CONNECTED,
    /** The status probe answered "cover open": nothing was sent (RETRY_WAIT, like NOT_CONNECTED). */
    NOT_READY_COVER_OPEN,
    /** The status probe answered "paper end": nothing was sent. */
    NOT_READY_PAPER_OUT,
    /** The status probe answered "error": nothing was sent. */
    NOT_READY_ERROR,
    /** Connected, then the write failed or timed out: part of it may have printed (UNKNOWN). */
    INTERRUPTED_AFTER_WRITE;

    /** No byte of the ticket reached the printer, so trying again cannot print it twice. */
    public boolean nothingWritten() {
        return this != ACCEPTED && this != INTERRUPTED_AFTER_WRITE;
    }
}
