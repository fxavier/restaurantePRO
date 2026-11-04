package com.xavier.backend.shared.exception;

import java.util.Map;

/**
 * The requested resource does not exist (or is not visible). Mapped to 404, with the stable {@code code} property
 * when one is given. Extend per module.
 */
public non-sealed class NotFoundException extends CodedException {

    /** An uncoded not-found (R1 style): the ProblemDetail has no {@code code} property. */
    public NotFoundException(String message) {
        super(null, message, Map.of(), null);
    }

    public NotFoundException(String code, String message) {
        super(code, message, Map.of(), null);
    }

    public NotFoundException(String code, String message, Map<String, ?> properties) {
        super(code, message, properties, null);
    }
}
