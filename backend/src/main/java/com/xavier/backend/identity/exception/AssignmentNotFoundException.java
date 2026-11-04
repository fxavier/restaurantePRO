package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

public class AssignmentNotFoundException extends NotFoundException {

    public AssignmentNotFoundException(UUID id) {
        super("Assignment " + id + " not found.");
    }
}
