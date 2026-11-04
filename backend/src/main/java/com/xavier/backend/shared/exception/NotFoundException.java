package com.xavier.backend.shared.exception;

/** The requested resource does not exist (or is not visible). Mapped to 404. Extend per module. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
