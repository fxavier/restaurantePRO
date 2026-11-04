package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

public class EmployeeNotFoundException extends NotFoundException {

    public EmployeeNotFoundException(UUID id) {
        super("Employee " + id + " not found.");
    }
}
