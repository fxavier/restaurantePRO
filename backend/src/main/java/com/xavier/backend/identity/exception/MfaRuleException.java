package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;

/** A well-formed second-factor request breaks a rule (422, D143). */
public class MfaRuleException extends BusinessRuleException {

    private MfaRuleException(String code, String message) {
        super(code, message);
    }

    /** Unknown, used, voided, expired, or another account's enrolment code. */
    public static MfaRuleException enrolmentCodeInvalid() {
        return new MfaRuleException("MFA_ENROLMENT_CODE_INVALID", "The enrolment code is not valid.");
    }

    /** An administrator does not reset their own factor: they use a recovery code. */
    public static MfaRuleException selfReset() {
        return new MfaRuleException("MFA_SELF_RESET",
                "You cannot reset your own second factor. Use a recovery code instead.");
    }

    public static MfaRuleException reasonRequired() {
        return new MfaRuleException("REASON_REQUIRED", "A reason is required.");
    }
}
