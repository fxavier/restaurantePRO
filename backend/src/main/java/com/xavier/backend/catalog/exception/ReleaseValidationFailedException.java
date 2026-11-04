package com.xavier.backend.catalog.exception;

import com.xavier.backend.catalog.model.dto.ReleaseValidationError;
import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.List;
import java.util.Map;

/**
 * The working copy breaks publication rules (422) with every problem listed in the ProblemDetail's
 * {@code errors} property; each error has its own stable {@code code}. The ProblemDetail itself has no
 * {@code code} (R1 contract, kept unchanged).
 */
public class ReleaseValidationFailedException extends BusinessRuleException {

    private final transient List<ReleaseValidationError> errors;

    public ReleaseValidationFailedException(List<ReleaseValidationError> errors) {
        super(null, "The catalog cannot be published: " + errors.size() + " problem(s).",
                Map.of("errors", List.copyOf(errors)));
        this.errors = List.copyOf(errors);
    }

    public List<ReleaseValidationError> getErrors() {
        return errors;
    }
}
