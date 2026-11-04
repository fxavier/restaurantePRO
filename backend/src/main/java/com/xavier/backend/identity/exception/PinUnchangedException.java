package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** The new PIN is the one the manager set, which the manager knows (422, code {@value #CODE}). */
public class PinUnchangedException extends BusinessRuleException {

    public static final String CODE = "PIN_UNCHANGED";

    public PinUnchangedException() {
        super(CODE, "The new PIN must differ from the one the manager set.");
    }
}
