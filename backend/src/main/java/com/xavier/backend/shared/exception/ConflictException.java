package com.xavier.backend.shared.exception;

import java.util.Map;

/**
 * The request conflicts with the current state: an illegal state transition, a duplicate, an operation
 * already in progress. Mapped to 409 (a stale {@code @Version} also maps to 409), with the stable {@code code}
 * property when one is given and any extra properties. Extend per module.
 */
public non-sealed class ConflictException extends CodedException {

    /** An uncoded conflict (R1 style): the ProblemDetail has no {@code code} property. */
    public ConflictException(String message) {
        super(null, message, Map.of(), null);
    }

    public ConflictException(String code, String message) {
        super(code, message, Map.of(), null);
    }

    public ConflictException(String code, String message, Map<String, ?> properties) {
        super(code, message, properties, null);
    }

    public ConflictException(String code, String message, Map<String, ?> properties, Throwable cause) {
        super(code, message, properties, cause);
    }
}
