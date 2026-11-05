package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/**
 * A support account is never linked to an employee: the employee's PIN would open the POS with no grant (422, code
 * {@value #CODE}).
 */
public class SupportAccountLinkedException extends BusinessRuleException {

    public static final String CODE = "SUPPORT_ACCOUNT_LINKED";

    public SupportAccountLinkedException() {
        super(CODE, "A support account cannot be linked to an employee.");
    }
}
