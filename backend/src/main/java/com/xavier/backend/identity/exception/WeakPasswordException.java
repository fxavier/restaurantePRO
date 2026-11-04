package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** A password does not meet the strong-password rule (spec §10). */
public class WeakPasswordException extends BusinessRuleException {

    public WeakPasswordException(String message) {
        super(message);
    }
}
