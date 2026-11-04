package com.xavier.backend.fiscal;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Issuing failed (D50 §7.6): nothing was numbered, the DRAFT stays with its {@code last_error} as a visible fiscal
 * pendency, and payments are kept. Renders as 422 with {@code code = FISCAL_PENDING} and {@code documentId}. It is an
 * {@link ErrorResponseException} because the shared handler keeps an ErrorResponse's ProblemDetail properties.
 */
public class FiscalIssueFailedException extends ErrorResponseException {

    public static final String CODE = "FISCAL_PENDING";

    private final UUID documentId;

    public FiscalIssueFailedException(UUID documentId, Throwable cause) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, problem(documentId), cause);
        this.documentId = documentId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    private static ProblemDetail problem(UUID documentId) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "The fiscal document could not be issued and stays pending; payments are kept. Try again later.");
        problem.setProperty("code", CODE);
        problem.setProperty("documentId", documentId);
        return problem;
    }
}
