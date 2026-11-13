package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** The account's or session's second-factor state does not allow the request (409, D141-D143). */
public class MfaConflictException extends ConflictException {

    private MfaConflictException(String code, String message) {
        super(code, message);
    }

    /** {@code /auth/mfa/verify} on a session that is not waiting for a second factor. */
    public static MfaConflictException notPending() {
        return new MfaConflictException("MFA_NOT_PENDING", "This session is not waiting for a second factor.");
    }

    /** The account has no active second factor. */
    public static MfaConflictException notEnrolled() {
        return new MfaConflictException("MFA_NOT_ENROLLED", "This account has no second factor.");
    }

    public static MfaConflictException alreadyEnrolled() {
        return new MfaConflictException("MFA_ALREADY_ENROLLED", "This account already has a second factor.");
    }

    /** No enrolment was started, or it is older than 10 minutes. */
    public static MfaConflictException noPendingEnrolment() {
        return new MfaConflictException("MFA_NO_PENDING_ENROLMENT",
                "There is no enrolment to confirm. Start the enrolment again.");
    }

    /** MFA is {@code OFF} and no key file is configured, so no secret can be stored. */
    public static MfaConflictException notConfigured() {
        return new MfaConflictException("MFA_NOT_CONFIGURED",
                "Second factors are not configured on this installation.");
    }
}
