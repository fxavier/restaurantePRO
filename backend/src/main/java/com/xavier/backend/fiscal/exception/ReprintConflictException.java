package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** Another reprint of the same document took this copy number (the unique key decided). 409; retry. */
public class ReprintConflictException extends ConflictException {

    public ReprintConflictException(UUID documentId) {
        super("Another reprint of document " + documentId + " is in progress");
    }
}
