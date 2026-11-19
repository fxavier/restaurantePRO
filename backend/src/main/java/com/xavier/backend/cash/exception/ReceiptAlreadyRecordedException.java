package com.xavier.backend.cash.exception;

import java.util.UUID;
import com.xavier.backend.shared.exception.ConflictException;

/**
 * Another transaction recorded the same payment's receipt, or refund, at the same moment
 * ({@code cash_movement_source_uk}).
 * 409 with code {@value #CODE}; the caller's retry finds the recorded movement.
 */
public class ReceiptAlreadyRecordedException extends ConflictException {

    public static final String CODE = "RECEIPT_ALREADY_RECORDED";

    public ReceiptAlreadyRecordedException(UUID paymentId) {
        this("receipt of payment", paymentId);
    }

    /** The same race for another settlement source, e.g. {@code "refund"}. */
    public ReceiptAlreadyRecordedException(String what, UUID sourceId) {
        super(CODE, "The " + what + " " + sourceId + " is being recorded by another request.");
    }
}
