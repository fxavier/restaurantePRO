package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.ConflictException;

/**
 * The terminal's state does not allow this action (e.g. a new code for an enrolled terminal). 409 with code
 * {@value #CODE}.
 */
public class IllegalTerminalStateException extends ConflictException {

    public static final String CODE = "ILLEGAL_TERMINAL_STATE";

    public IllegalTerminalStateException(String message) {
        super(CODE, message);
    }
}
