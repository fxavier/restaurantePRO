package com.xavier.backend.cash.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A cash business rule refused the request: 422 with the stable {@code code} property (PRD R2 conventions). It is an
 * {@link ErrorResponseException}, not a {@code BusinessRuleException}, because the shared handler renders an
 * ErrorResponse's own ProblemDetail with its properties, and a business-rule exception only as status and message
 * (the same reason as {@code catalog.ReleaseValidationFailedException}).
 */
public class CashRuleViolationException extends ErrorResponseException {

    private final CashRule rule;

    public CashRuleViolationException(CashRule rule, String detail) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, problem(rule, detail), null);
        this.rule = rule;
    }

    public CashRule rule() {
        return rule;
    }

    @Override
    public String getMessage() {
        return rule + ": " + getBody().getDetail();
    }

    private static ProblemDetail problem(CashRule rule, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, detail);
        problem.setProperty("code", rule.name());
        return problem;
    }
}
