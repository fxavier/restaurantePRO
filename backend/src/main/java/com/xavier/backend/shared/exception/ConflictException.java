package com.xavier.backend.shared.exception;

/**
 * The request conflicts with the current state: an illegal state transition, a duplicate, an operation
 * already in progress. Mapped to 409 (a stale {@code @Version} also maps to 409). Extend per module.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
