package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.ConflictException;

/** 409 with code {@value #CODE}. */
public class EmployeeCodeTakenException extends ConflictException {

    public static final String CODE = "EMPLOYEE_CODE_TAKEN";

    public EmployeeCodeTakenException(String code) {
        super(CODE, "Employee code " + code + " is already taken.");
    }
}
