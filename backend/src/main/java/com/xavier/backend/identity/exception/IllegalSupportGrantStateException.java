package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** The support grant has already ended (revoked or expired), so it cannot be revoked (409, code {@value #CODE}). */
public class IllegalSupportGrantStateException extends ConflictException {

    public static final String CODE = "SUPPORT_GRANT_ENDED";

    public IllegalSupportGrantStateException() {
        super(CODE, "The support grant has already ended.");
    }
}
