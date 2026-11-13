package com.xavier.backend.identity.oidc.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** 422 {@code SUPPORT_ACCOUNT_CANNOT_LINK}: support access stays local, with its grant and second factor (D145). */
public class SupportAccountCannotLinkException extends BusinessRuleException {

    public static final String CODE = "SUPPORT_ACCOUNT_CANNOT_LINK";

    public SupportAccountCannotLinkException() {
        super(CODE, "A support account cannot be linked to an identity provider.");
    }
}
