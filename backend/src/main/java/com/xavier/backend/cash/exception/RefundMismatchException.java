package com.xavier.backend.cash.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/**
 * The refund was already recorded with another session, method or amount: a replay must carry the same facts. 409
 * {@code REFUND_MISMATCH}; the recorded movement is unchanged.
 */
public class RefundMismatchException extends ConflictException {

    public static final String CODE = "REFUND_MISMATCH";

    public RefundMismatchException(UUID refundId) {
        super(CODE, "Refund " + refundId + " was already recorded with another session, method or amount.");
    }
}
