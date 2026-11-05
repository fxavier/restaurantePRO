package com.xavier.backend.settlement.reconciliation.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.Map;
import java.util.UUID;

/** The request conflicts with what reconciliation already recorded: 409 with the stable {@code code}. */
public class ReconciliationConflictException extends ConflictException {

    private ReconciliationConflictException(ReconciliationRule rule, String detail, Map<String, ?> properties) {
        super(rule.name(), detail, properties);
    }

    public static ReconciliationConflictException alreadyImported(UUID batchId) {
        return new ReconciliationConflictException(ReconciliationRule.STATEMENT_ALREADY_IMPORTED,
                "This statement was already imported as batch " + batchId + ".", Map.of("batchId", batchId));
    }

    public static ReconciliationConflictException entryResolved(UUID entryId) {
        return new ReconciliationConflictException(ReconciliationRule.ENTRY_RESOLVED, "Reconciliation entry "
                + entryId + " is already resolved.", Map.of());
    }
}
