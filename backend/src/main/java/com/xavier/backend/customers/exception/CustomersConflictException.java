package com.xavier.backend.customers.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** A customers state conflict: 409 with a stable {@code code} (PRD R3 conventions). */
public class CustomersConflictException extends ConflictException {

    public CustomersConflictException(String code, String message) {
        super(code, message);
    }

    /** A reservation is CONSUMED or RELEASED and cannot do {@code action}. */
    public static CustomersConflictException reservationClosed(UUID chargeId, Object status, String action) {
        return new CustomersConflictException("RESERVATION_NOT_ACTIVE",
                "The credit reservation of charge " + chargeId + " is " + status + " and cannot " + action + ".");
    }
}
