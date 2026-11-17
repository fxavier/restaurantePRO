package com.xavier.backend.access;

import org.springframework.security.access.AccessDeniedException;

/**
 * A sensitive permission was used without a fresh second factor (D184). For a BackOffice account it is the RFC 9470
 * challenge (401, {@code MFA_STEP_UP_REQUIRED}, {@code acr_values="2"}, {@code max_age}): the client steps up through
 * the BFF, then repeats the request with the same {@code Idempotency-Key} (a 401 is never stored, D47). A POS (PIN)
 * session cannot step up: 403 {@code MFA_ACCOUNT_REQUIRED}, it must use a BackOffice account.
 */
public class StepUpRequiredException extends AccessDeniedException {

    public static final String STEP_UP_REQUIRED = "MFA_STEP_UP_REQUIRED";
    public static final String ACCOUNT_REQUIRED = "MFA_ACCOUNT_REQUIRED";
    /** The level of authentication the challenge asks for ({@code acr_values}). */
    public static final String REQUIRED_ACR = Assurance.SECOND_FACTOR;

    private final String code;

    private StepUpRequiredException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** The token has no second factor ({@code acr < 2}) or proved it longer ago than the step-up max age. */
    public static StepUpRequiredException stepUpRequired() {
        return new StepUpRequiredException(STEP_UP_REQUIRED, "Confirm your second factor to continue.");
    }

    /** A POS (PIN) session uses a sensitive permission outside the POS gate. */
    public static StepUpRequiredException accountRequired() {
        return new StepUpRequiredException(ACCOUNT_REQUIRED,
                "Use a BackOffice account with a second factor for this action.");
    }

    /** The stable ProblemDetail {@code code}. */
    public String code() {
        return code;
    }

    /** Whether authenticating again can satisfy it (a BackOffice account), not for a POS session. */
    public boolean canStepUp() {
        return STEP_UP_REQUIRED.equals(code);
    }
}
