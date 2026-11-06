package com.xavier.backend.audit.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

public class AuditEventNotFoundException extends NotFoundException {

    public AuditEventNotFoundException(UUID id) {
        super("Audit event " + id + " not found.");
    }
}
