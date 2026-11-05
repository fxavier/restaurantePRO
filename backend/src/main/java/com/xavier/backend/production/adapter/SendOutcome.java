package com.xavier.backend.production.adapter;

/** What a send to a printer achieved (D57). */
public enum SendOutcome {
    /** Every byte was written and the connection closed cleanly: SENT. */
    ACCEPTED,
    /** No connection: nothing reached the printer, so a retry cannot duplicate (RETRY_WAIT). */
    NOT_CONNECTED,
    /** Connected, then the write failed or timed out: part of it may have printed (UNKNOWN). */
    INTERRUPTED_AFTER_WRITE
}
