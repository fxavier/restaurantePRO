package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** The command does not fit the document's state (issuing twice, reprinting a draft). 409 with code {@value #CODE}. */
public class IllegalDocumentStateException extends ConflictException {

    public static final String CODE = "ILLEGAL_DOCUMENT_STATE";

    public IllegalDocumentStateException(String message) {
        super(CODE, message);
    }
}
