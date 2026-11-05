package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** A concurrent request drafted this source's document first (the source unique index decided). 409; retry reads it. */
public class SaleDraftConflictException extends ConflictException {

    public SaleDraftConflictException(UUID sourceId) {
        super("The sale document of " + sourceId + " is being created by another request");
    }
}
