package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** An employee can be linked to at most one BackOffice account. */
public class EmployeeAccountTakenException extends ConflictException {

    public EmployeeAccountTakenException(UUID employeeId) {
        super("Employee " + employeeId + " already has an account.");
    }
}
