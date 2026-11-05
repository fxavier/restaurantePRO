package com.xavier.backend.cash.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/**
 * D79 (2): the register's movements changed since the closing was drafted (an adjustment), so what it compared is no
 * longer what the register should hold. 409 {@code CLOSING_STALE}: reject it, and the next attempt re-computes.
 */
public class ClosingStaleException extends ConflictException {

    public static final String CODE = "CLOSING_STALE";

    public ClosingStaleException(UUID closingId, String methodCode) {
        super(CODE, "The expected " + methodCode + " amount of cash closing " + closingId
                + " changed since it was drafted: reject it and draft it again.");
    }
}
