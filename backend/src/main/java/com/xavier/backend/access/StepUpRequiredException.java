package com.xavier.backend.access;

import org.springframework.security.access.AccessDeniedException;

/**
 * A BackOffice account holds the sensitive permission but has not proved a fresh second factor (D142). A 403
 * ProblemDetail with a stable {@code code} and {@code stepUpUri}: the client steps up, then repeats the request with
 * the same {@code Idempotency-Key} (a 403 is never stored, D47).
 */
public class StepUpRequiredException extends AccessDeniedException {

    public static final String STEP_UP_REQUIRED = "MFA_STEP_UP_REQUIRED";
    public static final String ENROLMENT_REQUIRED = "MFA_ENROLMENT_REQUIRED";
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

    /** The stable ProblemDetail {@code code}. */
    public String code() {
        return code;
    }
}
