package com.xavier.backend.cash.exception;

import java.util.UUID;
import com.xavier.backend.shared.exception.ConflictException;

/**
 * Another transaction recorded the same payment's receipt at the same moment ({@code cash_movement_source_uk}).
 * 409; the caller's retry finds the recorded movement.
 */
public class ReceiptAlreadyRecordedException extends ConflictException {

    public ReceiptAlreadyRecordedException(UUID paymentId) {
        super("The receipt of payment " + paymentId + " is being recorded by another request.");
    }
}
