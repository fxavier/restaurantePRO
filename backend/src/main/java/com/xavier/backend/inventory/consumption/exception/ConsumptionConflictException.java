package com.xavier.backend.inventory.consumption.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** A consumption exception command its state does not allow: 409 with a stable {@code code}. */
public class ConsumptionConflictException extends ConflictException {

    public ConsumptionConflictException(String code, String message) {
        super(code, message);
    }

    public static ConsumptionConflictException alreadyResolved(UUID exceptionId) {
        return new ConsumptionConflictException("CONSUMPTION_EXCEPTION_RESOLVED",
                "Consumption exception " + exceptionId + " is already resolved.");
    }
}
