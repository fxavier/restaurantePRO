package com.xavier.backend.organization.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** The terminal's state does not allow this action (e.g. a new code for an enrolled terminal). 409. */
public class IllegalTerminalStateException extends ConflictException {

    public IllegalTerminalStateException(String message) {
        super(message);
    }
}
