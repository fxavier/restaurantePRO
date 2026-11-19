package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** 409 with code {@value #CODE}. */
public class DuplicateAssignmentException extends ConflictException {

    public static final String CODE = "ROLE_ALREADY_ASSIGNED";

    public DuplicateAssignmentException() {
        super(CODE, "The employee already has this role in this store.");
    }
}
