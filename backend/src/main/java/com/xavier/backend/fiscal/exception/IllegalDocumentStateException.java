package com.xavier.backend.fiscal.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** The command does not fit the document's state (issuing twice, reprinting a draft). 409. */
public class IllegalDocumentStateException extends ConflictException {

    public IllegalDocumentStateException(String message) {
        super(message);
    }
}
