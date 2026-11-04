package com.xavier.backend.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The new PIN is the one the manager set, which the manager knows (422, code {@value #CODE}). An
 * {@link ErrorResponseException} so the ProblemDetail keeps its {@code code} property.
 */
public class PinUnchangedException extends ErrorResponseException {

    public static final String CODE = "PIN_UNCHANGED";

    public PinUnchangedException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "The new PIN must differ from the one the manager set.");
        problem.setProperty("code", CODE);
        return problem;
    }
}
