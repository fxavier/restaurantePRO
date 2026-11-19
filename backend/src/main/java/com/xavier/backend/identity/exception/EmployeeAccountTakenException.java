package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.ConflictException;
import java.util.UUID;

/** An employee can be linked to at most one BackOffice account. 409 with code {@value #CODE}. */
public class EmployeeAccountTakenException extends ConflictException {

    public static final String CODE = "EMPLOYEE_ACCOUNT_TAKEN";

    public EmployeeAccountTakenException(UUID employeeId) {
        super(CODE, "Employee " + employeeId + " already has an account.");
    }
}
