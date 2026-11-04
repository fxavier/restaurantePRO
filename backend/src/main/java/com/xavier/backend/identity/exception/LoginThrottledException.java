package com.xavier.backend.identity.exception;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Too many password attempts for this username from this address (429, code {@value #CODE}, {@code Retry-After} in
 * seconds). An {@link ErrorResponseException} so the shared handler keeps the status, header and {@code code}.
 */
public class LoginThrottledException extends ErrorResponseException {

    public static final String CODE = "LOGIN_THROTTLED";

    public LoginThrottledException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, problem(), null);
        getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfter.toSeconds())));
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many login attempts. Try again later.");
        problem.setProperty("code", CODE);
        return problem;
    }
}
