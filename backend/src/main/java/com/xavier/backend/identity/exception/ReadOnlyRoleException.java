package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** The administrator role always holds every permission; it is maintained by migrations, not edited. */
public class ReadOnlyRoleException extends BusinessRuleException {

    public ReadOnlyRoleException() {
        super("The administrator role cannot be changed.");
    }
}
