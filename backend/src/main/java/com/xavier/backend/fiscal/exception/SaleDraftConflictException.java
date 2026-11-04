package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** A concurrent request drafted this order's document first (the source unique index decided). 409; retry reads it. */
public class SaleDraftConflictException extends ConflictException {

    public SaleDraftConflictException(UUID orderId) {
        super("The sale document of order " + orderId + " is being created by another request");
    }
}
