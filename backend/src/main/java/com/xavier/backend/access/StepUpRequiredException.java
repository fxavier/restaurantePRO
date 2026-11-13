package com.xavier.backend.access;

import org.springframework.security.access.AccessDeniedException;

/**
 * A sensitive permission was used without a fresh second factor (D142). A 403 ProblemDetail with a stable
 * {@code code} and, when stepping up can help, {@code stepUpUri}: the client steps up, then repeats the request with
 * the same {@code Idempotency-Key} (a 403 is never stored, D47). A POS session gets {@code MFA_ACCOUNT_REQUIRED},
 * without {@code stepUpUri}: it must use a BackOffice account.
 */
public class StepUpRequiredException extends AccessDeniedException {

    public static final String STEP_UP_REQUIRED = "MFA_STEP_UP_REQUIRED";
    public static final String ENROLMENT_REQUIRED = "MFA_ENROLMENT_REQUIRED";
    public static final String ACCOUNT_REQUIRED = "MFA_ACCOUNT_REQUIRED";
    /** Where the client proves its second factor; a ProblemDetail property of every step-up refusal. */
    public static final String STEP_UP_URI = "/api/v1/auth/mfa/step-up";

    private final String code;

    private StepUpRequiredException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** The account has a second factor, but this session never proved it or proved it too long ago. */
    public static StepUpRequiredException stepUpRequired() {
        return new StepUpRequiredException(STEP_UP_REQUIRED, "Confirm your second factor to continue.");
    }

    /** The account has no second factor yet: it enrols first. */
    public static StepUpRequiredException enrolmentRequired() {
        return new StepUpRequiredException(ENROLMENT_REQUIRED, "Set up a second factor to continue.");
    }

    /** A POS (PIN) session uses a sensitive permission outside the POS gate while step-up is enforced. */
    public static StepUpRequiredException accountRequired() {
        return new StepUpRequiredException(ACCOUNT_REQUIRED,
                "Use a BackOffice account with a second factor for this action.");
    }

    /** The stable ProblemDetail {@code code}. */
    public String code() {
        return code;
    }

    /** Where to prove the second factor, or {@code null} when stepping up cannot help (a POS session). */
    public String stepUpUri() {
        return ACCOUNT_REQUIRED.equals(code) ? null : STEP_UP_URI;
    }
}
