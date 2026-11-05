package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** Support access is granted only to an account created as a support account (D83; 422, code {@value #CODE}). */
public class SupportAccountRequiredException extends BusinessRuleException {

    public static final String CODE = "SUPPORT_ACCOUNT_REQUIRED";

    public SupportAccountRequiredException() {
        super(CODE, "Support access is granted only to a support account.");
    }
}
