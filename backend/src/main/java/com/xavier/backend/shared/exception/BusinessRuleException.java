package com.xavier.backend.shared.exception;

/** A well-formed request breaks a business rule (spec §8.4). Mapped to 422. Extend per module. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
