package com.xavier.backend.dining.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A dining refusal whose ProblemDetail carries a stable {@code code} property (PRD R2 endpoint conventions), e.g.
 * {@code TABLE_OCCUPIED}. It is an {@link ErrorResponseException} because the shared handler renders an
 * ErrorResponse's own ProblemDetail with its properties, but a {@code BusinessRuleException} or
 * {@code ConflictException} only as status and message.
 */
public abstract class DiningProblemException extends ErrorResponseException {

    private final String code;

    protected DiningProblemException(HttpStatus status, String code, String detail) {
        super(status, problem(status, code, detail), null);
        this.code = code;
    }

    public String code() {
        return code;
    }

    @Override
    public String getMessage() {
        return code + ": " + getBody().getDetail();
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
