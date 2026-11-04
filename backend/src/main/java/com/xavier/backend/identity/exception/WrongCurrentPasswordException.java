package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** A password change gave the wrong current password. 422, not 401: the session itself is still valid. */
public class WrongCurrentPasswordException extends BusinessRuleException {

    public WrongCurrentPasswordException() {
        super("The current password is not correct.");
    }
}
