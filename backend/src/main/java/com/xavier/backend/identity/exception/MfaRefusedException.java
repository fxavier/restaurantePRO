package com.xavier.backend.identity.exception;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A second-factor refusal whose status is none of 404/409/422 (D139-D143, PRD R5 S56): 401 for a wrong code or
 * password and an expired pending login, 403 where the request may not go on without a factor, 429 with
 * {@code Retry-After} while the factor is locked. Like {@link LoginThrottledException}, deliberately an
 * {@link ErrorResponseException}: the shared coded types render only 404/409/422/503, and the shared handler keeps
 * this status, body and header. One class, one factory per stable {@code code}.
 */
public class MfaRefusedException extends ErrorResponseException {

    public static final String INVALID_CODE = "MFA_INVALID_CODE";
    public static final String PENDING_EXPIRED = "MFA_PENDING_EXPIRED";
    public static final String WRONG_CURRENT_PASSWORD = "WRONG_CURRENT_PASSWORD";
    public static final String LOCKED = "MFA_LOCKED";
    public static final String REQUIRED = "MFA_REQUIRED";
    public static final String NOT_ENROLLED = "MFA_NOT_ENROLLED";
    public static final String ENROLMENT_CODE_REQUIRED = "MFA_ENROLMENT_CODE_REQUIRED";

    private final String code;

    private MfaRefusedException(HttpStatus status, String code, String detail) {
        super(status, problem(status, code, detail), null);
        this.code = code;
    }

    /** The code or recovery code is wrong, was already used, or belongs to an earlier step. */
    public static MfaRefusedException invalidCode() {
        return new MfaRefusedException(HttpStatus.UNAUTHORIZED, INVALID_CODE, "The code is not valid.");
    }

    /** The pending login is older than 5 minutes: log in again. */
    public static MfaRefusedException pendingExpired() {
        return new MfaRefusedException(HttpStatus.UNAUTHORIZED, PENDING_EXPIRED,
                "The login waited too long for its second factor. Log in again.");
    }

    public static MfaRefusedException wrongCurrentPassword() {
        return new MfaRefusedException(HttpStatus.UNAUTHORIZED, WRONG_CURRENT_PASSWORD,
                "The current password is not correct.");
    }

    /** Too many wrong codes: second-factor checks of this account are locked for {@code retryAfter}. */
    public static MfaRefusedException locked(Duration retryAfter) {
        MfaRefusedException e = new MfaRefusedException(HttpStatus.TOO_MANY_REQUESTS, LOCKED,
                "Too many wrong codes. Try again later.");
        e.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfter.toSeconds())));
        return e;
    }

    /** A remote request on a session that never proved a second factor (D140). */
    public static MfaRefusedException required() {
        return new MfaRefusedException(HttpStatus.FORBIDDEN, REQUIRED,
                "Confirm your second factor before using this session from outside the store network.");
    }

    /** A login that needs a second factor, by an account without one, without an enrolment code (D141). */
    public static MfaRefusedException notEnrolledAtLogin() {
        return new MfaRefusedException(HttpStatus.FORBIDDEN, NOT_ENROLLED,
                "This account has no second factor. Ask an administrator for an enrolment code.");
    }

    /** Enrolling remotely, or as a support account, needs an administrator's enrolment code (D143, D145). */
    public static MfaRefusedException enrolmentCodeRequired() {
        return new MfaRefusedException(HttpStatus.FORBIDDEN, ENROLMENT_CODE_REQUIRED,
                "Enrolling from here needs an enrolment code from an administrator.");
    }

    public String code() {
        return code;
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
