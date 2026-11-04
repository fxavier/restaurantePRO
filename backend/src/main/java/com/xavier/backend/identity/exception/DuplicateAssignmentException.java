package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.ConflictException;

public class DuplicateAssignmentException extends ConflictException {

    public DuplicateAssignmentException() {
        super("The employee already has this role in this store.");
    }
}
