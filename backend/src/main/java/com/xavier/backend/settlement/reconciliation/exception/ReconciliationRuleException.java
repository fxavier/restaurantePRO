package com.xavier.backend.settlement.reconciliation.exception;

import com.xavier.backend.settlement.reconciliation.model.dto.StatementError;
import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.List;
import java.util.Map;

/** A reconciliation rule refused the request: 422 with the stable {@code code}. */
public class ReconciliationRuleException extends BusinessRuleException {

    private ReconciliationRuleException(ReconciliationRule rule, String detail, Map<String, ?> properties) {
        super(rule.name(), detail, properties);
    }

    public static ReconciliationRuleException statementInvalid(List<StatementError> errors) {
        return new ReconciliationRuleException(ReconciliationRule.STATEMENT_INVALID, "The statement has "
                + errors.size() + " error(s); nothing was imported.", Map.of("errors", List.copyOf(errors)));
    }

    public static ReconciliationRuleException statementInvalid(String field, String message) {
        return statementInvalid(List.of(new StatementError(0, field, message)));
    }

    public static ReconciliationRuleException noStatementSource(String methodCode) {
        return new ReconciliationRuleException(ReconciliationRule.NO_STATEMENT_SOURCE, "The provider of " + methodCode
                + " cannot fetch statements: import its statement file instead.", Map.of());
    }

    public static ReconciliationRuleException notAPendingCorrection(java.util.UUID adjustmentId) {
        return new ReconciliationRuleException(ReconciliationRule.NOT_A_PENDING_CORRECTION, "Tax rounding adjustment "
                + adjustmentId + " was absorbed by the residual document: there is nothing to correct.", Map.of());
    }
}
