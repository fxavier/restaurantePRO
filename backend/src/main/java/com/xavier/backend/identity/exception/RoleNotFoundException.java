package com.xavier.backend.identity.exception;

import com.xavier.backend.shared.exception.NotFoundException;
import java.util.UUID;

public class RoleNotFoundException extends NotFoundException {

    public RoleNotFoundException(UUID id) {
        super("Role " + id + " not found.");
    }
}
