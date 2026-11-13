package com.xavier.backend.production.adapter;

/** What a send to a printer achieved (D57, D157), and what it says about the printer. */
public enum SendOutcome {
    /** Every byte was written and the connection closed cleanly: SENT. */
    ACCEPTED("OK"),
    /** No connection: nothing reached the printer, so a retry cannot duplicate (RETRY_WAIT). */
    NOT_CONNECTED("NOT_CONNECTED"),
    /** The status probe answered "cover open": nothing was sent (RETRY_WAIT, like NOT_CONNECTED). */
    NOT_READY_COVER_OPEN("COVER_OPEN"),
    /** The status probe answered "paper end": nothing was sent. */
    NOT_READY_PAPER_OUT("PAPER_OUT"),
    /** The status probe answered "error": nothing was sent. */
    NOT_READY_ERROR("PRINTER_ERROR"),
    /** Connected, then the write failed or timed out: part of it may have printed (UNKNOWN). */
    INTERRUPTED_AFTER_WRITE("INTERRUPTED");

    private final String printerStatus;

    SendOutcome(String printerStatus) {
        this.printerStatus = printerStatus;
    }

    /** No byte of the ticket reached the printer, so trying again cannot print it twice. */
    public boolean nothingWritten() {
        return this != ACCEPTED && this != INTERRUPTED_AFTER_WRITE;
    }

    /** The printer's state as this send found it; for a send that wrote nothing, also the job's {@code last_error}. */
    public String printerStatus() {
        return printerStatus;
    }
}
