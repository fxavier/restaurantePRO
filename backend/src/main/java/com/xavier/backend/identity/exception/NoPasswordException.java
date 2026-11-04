package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** A POS (employee + PIN) session has no password to change. */
public class NoPasswordException extends BusinessRuleException {

    public NoPasswordException() {
        super("This session has no password: log in with a username and password to change it.");
    }
}
