package com.xavier.backend.production.adapter;

/**
 * What a send achieved (D57) and, when the status probe answered (D157), whether the paper is near its end (the
 * ticket was still sent).
 */
public record SendResult(SendOutcome outcome, boolean paperNearEnd) {

    public static SendResult of(SendOutcome outcome) {
        return new SendResult(outcome, false);
    }

    /** {@code printer.last_status}: the outcome's, or PAPER_NEAR_END for a send the probe warned about. */
    public String printerStatus() {
        return paperNearEnd ? "PAPER_NEAR_END" : outcome.printerStatus();
    }
}
