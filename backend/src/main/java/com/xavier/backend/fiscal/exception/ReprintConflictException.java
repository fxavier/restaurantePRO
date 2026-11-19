package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/**
 * Another reprint of the same document took this copy number (the unique key decided). 409 with code
 * {@value #CODE}, the code production's ticket reprint race already uses; retry.
 */
public class ReprintConflictException extends ConflictException {

    public static final String CODE = "REPRINT_IN_PROGRESS";

    public ReprintConflictException(UUID documentId) {
        super(CODE, "Another reprint of document " + documentId + " is in progress");
    }
}
