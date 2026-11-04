package com.xavier.backend.shared.exception;

import java.util.Map;

/**
 * A well-formed request breaks a business rule (spec §8.4). Mapped to 422, with the stable {@code code} property
 * when one is given (PRD R2: every R2 business rule has one) and any extra properties. Extend per module.
 */
public non-sealed class BusinessRuleException extends CodedException {

    /** An uncoded rule (R1 style): the ProblemDetail has no {@code code} property. */
    public BusinessRuleException(String message) {
        super(null, message, Map.of(), null);
    }

    public BusinessRuleException(String code, String message) {
        super(code, message, Map.of(), null);
    }

    public BusinessRuleException(String code, String message, Map<String, ?> properties) {
        super(code, message, properties, null);
    }

    public BusinessRuleException(String code, String message, Map<String, ?> properties, Throwable cause) {
        super(code, message, properties, cause);
    }
}
