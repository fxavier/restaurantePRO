package com.xavier.backend.fiscal;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.Map;
import java.util.UUID;

/**
 * Issuing failed (D50 §7.6): nothing was numbered, the DRAFT stays with its {@code last_error} as a visible fiscal
 * pendency, and payments are kept. Renders as 422 with {@code code = FISCAL_PENDING} and {@code documentId}.
 */
public class FiscalIssueFailedException extends BusinessRuleException {

    public static final String CODE = "FISCAL_PENDING";

    private final UUID documentId;

    public FiscalIssueFailedException(UUID documentId, Throwable cause) {
        super(CODE, "The fiscal document could not be issued and stays pending; payments are kept. Try again later.",
                Map.of("documentId", documentId), cause);
        this.documentId = documentId;
    }

    public UUID getDocumentId() {
        return documentId;
    }
}
