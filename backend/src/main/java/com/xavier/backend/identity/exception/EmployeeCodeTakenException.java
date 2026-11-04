package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.ConflictException;

public class EmployeeCodeTakenException extends ConflictException {

    public EmployeeCodeTakenException(String code) {
        super("Employee code " + code + " is already taken.");
    }
}
