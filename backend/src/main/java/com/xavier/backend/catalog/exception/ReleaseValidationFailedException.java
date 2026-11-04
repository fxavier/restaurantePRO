package com.xavier.backend.catalog.exception;

import com.xavier.backend.catalog.model.dto.ReleaseValidationError;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The working copy breaks publication rules (422) with every problem listed in the ProblemDetail's
 * {@code errors} property. It is an {@link ErrorResponseException}, not a {@code BusinessRuleException}, because
 * the shared handler renders an ErrorResponse's own ProblemDetail (with properties) but a business-rule
 * exception only as status + message.
 */
public class ReleaseValidationFailedException extends ErrorResponseException {

    private final transient List<ReleaseValidationError> errors;

    public ReleaseValidationFailedException(List<ReleaseValidationError> errors) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, problem(errors), null);
        this.errors = List.copyOf(errors);
    }

    public List<ReleaseValidationError> getErrors() {
        return errors;
    }

    private static ProblemDetail problem(List<ReleaseValidationError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "The catalog cannot be published: " + errors.size() + " problem(s).");
        problem.setProperty("errors", errors);
        return problem;
    }
}
