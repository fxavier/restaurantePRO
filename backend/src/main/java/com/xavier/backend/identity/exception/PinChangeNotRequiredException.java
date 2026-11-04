package com.xavier.backend.identity.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * {@code POST /auth/pos-pin} serves only a POS session whose PIN a manager set (422, code
 * {@value #CODE}). An {@link ErrorResponseException} so the ProblemDetail keeps its {@code code} property.
 */
public class PinChangeNotRequiredException extends ErrorResponseException {

    public static final String CODE = "PIN_CHANGE_NOT_REQUIRED";

    public PinChangeNotRequiredException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "Only a POS session whose PIN was reset by a manager sets a new PIN here.");
        problem.setProperty("code", CODE);
        return problem;
    }
}
