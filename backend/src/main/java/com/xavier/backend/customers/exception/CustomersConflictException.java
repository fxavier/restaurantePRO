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

    /** {@code UNIQUE(customer_id, digits)}. */
    public static CustomersConflictException phoneExists(UUID customerId, String digits) {
        return new CustomersConflictException("PHONE_EXISTS",
                "Customer " + customerId + " already has the phone " + digits + ".");
    }

    /** Two first limit changes opened the account at once; the loser retries. */
    public static CustomersConflictException accountExists(UUID customerId) {
        return new CustomersConflictException("CREDIT_ACCOUNT_EXISTS",
                "The credit account of customer " + customerId + " was opened at the same time; retry.");
    }
}
