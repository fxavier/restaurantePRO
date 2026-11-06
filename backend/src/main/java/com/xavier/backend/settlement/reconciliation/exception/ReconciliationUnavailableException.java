package com.xavier.backend.settlement.reconciliation.exception;

import com.xavier.backend.shared.exception.UnavailableException;
import java.util.Map;

/** The provider did not answer a statement fetch in time: 503, retryable, nothing imported. */
public class ReconciliationUnavailableException extends UnavailableException {

    private ReconciliationUnavailableException(String detail) {
        super(ReconciliationRule.STATEMENT_SOURCE_TIMEOUT.name(), detail, Map.of());
    }

    public static ReconciliationUnavailableException sourceTimeout(String methodCode) {
        return new ReconciliationUnavailableException("The provider of " + methodCode
                + " did not send its statement in time; nothing was imported. Try again later.");
    }
}
