package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** The installation must keep one active account with the administrator role, or nobody could manage it. */
public class LastAdministratorException extends BusinessRuleException {

    public LastAdministratorException() {
        super("This is the last active administrator: make another account administrator first.");
    }
}
