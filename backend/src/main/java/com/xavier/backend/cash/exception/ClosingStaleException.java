package com.xavier.backend.cash.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/**
 * D79 (2): the register's movements changed since the closing was drafted (an adjustment), so what it compared is no
 * longer what the register should hold, or the closing is of an earlier round of a reopened day. 409
 * {@code CLOSING_STALE}: reject it, and the next attempt re-computes.
 */
public class ClosingStaleException extends ConflictException {

    public static final String CODE = "CLOSING_STALE";

    private ClosingStaleException(String detail) {
        super(CODE, detail);
    }

    public ClosingStaleException(UUID closingId, String methodCode) {
        this("The expected " + methodCode + " amount of cash closing " + closingId
                + " changed since it was drafted: reject it and draft it again.");
    }

    /** The day was reopened financially after the closing was drafted: it belongs to an earlier round. */
    public static ClosingStaleException earlierRound(UUID closingId, int closingRound, int currentRound) {
        return new ClosingStaleException("Cash closing " + closingId + " is of round " + closingRound
                + ", but the day is in round " + currentRound + ": draft a closing of the current round.");
    }
}
