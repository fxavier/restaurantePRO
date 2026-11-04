package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/**
 * {@code POST /auth/pos-pin} serves only a POS session whose PIN a manager set (422, code {@value #CODE}).
 */
public class PinChangeNotRequiredException extends BusinessRuleException {

    public static final String CODE = "PIN_CHANGE_NOT_REQUIRED";

    public PinChangeNotRequiredException() {
        super(CODE, "Only a POS session whose PIN was reset by a manager sets a new PIN here.");
    }
}
