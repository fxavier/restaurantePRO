package com.xavier.backend.dining.exception;

import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * The occupation is not open (§8.3 OPEN → CLOSED): it is already closed ({@code OCCUPATION_CLOSED}), or the order
 * has no open occupation ({@code NO_OPEN_OCCUPATION}). 409.
 */
public class IllegalOccupationStateException extends DiningProblemException {

    private IllegalOccupationStateException(String code, String detail) {
        super(HttpStatus.CONFLICT, code, detail);
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
