package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** One active grant per support account (D83, {@code support_grant_one_active_uk}; 409, code {@value #CODE}). */
public class SupportGrantActiveException extends ConflictException {

    public static final String CODE = "SUPPORT_GRANT_ACTIVE";

    public SupportGrantActiveException() {
        super(CODE, "The account already holds an active support grant.");
    }
}
