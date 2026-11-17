package com.xavier.bridge;

/** What a send achieved and whether the status probe warned of the paper's end (the ticket was still sent). */
record SendResult(SendOutcome outcome, boolean paperNearEnd) {

    static SendResult of(SendOutcome outcome) {
        return new SendResult(outcome, false);
    }
}
