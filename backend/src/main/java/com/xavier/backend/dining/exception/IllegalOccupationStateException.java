package com.xavier.backend.dining.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/**
 * The occupation is not open (§8.3 OPEN → CLOSED): it is already closed ({@code OCCUPATION_CLOSED}), or the order
 * has no open occupation ({@code NO_OPEN_OCCUPATION}). 409.
 */
public class IllegalOccupationStateException extends ConflictException {

    private IllegalOccupationStateException(String code, String detail) {
        super(code, detail);
    }

    public static IllegalOccupationStateException closed(UUID occupationId) {
        return new IllegalOccupationStateException("OCCUPATION_CLOSED",
                "Table occupation " + occupationId + " is already closed.");
    }

    public static IllegalOccupationStateException noOpenOccupation(UUID orderId) {
        return new IllegalOccupationStateException("NO_OPEN_OCCUPATION",
                "Order " + orderId + " has no open table occupation.");
    }
}
