package com.xavier.backend.inventory.consumption.exception;

import com.xavier.backend.shared.exception.BusinessRuleException;
import java.util.Map;
import java.util.UUID;

/** A reprocess that still cannot consume its line: 422 {@code CONSUMPTION_UNRESOLVED} with the reason. */
public class ConsumptionRuleException extends BusinessRuleException {

    public ConsumptionRuleException(String code, String message, Map<String, ?> properties) {
        super(code, message, properties);
    }

    public static ConsumptionRuleException unresolved(UUID exceptionId, Object kind) {
        return new ConsumptionRuleException("CONSUMPTION_UNRESOLVED", "Consumption exception " + exceptionId
                + " still cannot be consumed: " + kind + ". Fix it and reprocess again.", Map.of("kind", kind));
    }
}
